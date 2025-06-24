import os
import requests
from bs4 import BeautifulSoup
from urllib.parse import urljoin
import time
import zipfile
import shutil
import argparse
import concurrent.futures
from tqdm import tqdm
import glob
import re
import tempfile

# --- METADATA AND ARCHIVING FUNCTIONS ---

def generate_comic_info_xml(manga_title, bookmarks, total_page_count):
    """
    Generates the most robust ComicInfo.xml, with a full page list and types.
    """
    manga_title = manga_title.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    
    xml_content = f"""<?xml version="1.0" encoding="utf-8"?>
<ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <Series>{manga_title}</Series>
  <Title>{manga_title}</Title>
  <PageCount>{total_page_count}</PageCount>
  <Pages>
"""
    bookmark_map = {page_index: title for page_index, title in bookmarks}
    first_bookmark_page = bookmarks[0][0] if bookmarks else -1

    for i in range(total_page_count):
        if i in bookmark_map:
            sanitized_bookmark = bookmark_map[i].replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('"', '&quot;')
            page_type = 'FrontCover' if i == first_bookmark_page else 'Story'
            xml_content += f'    <Page Image="{i}" Bookmark="{sanitized_bookmark}" Type="{page_type}" />\n'
        else:
            xml_content += f'    <Page Image="{i}" />\n'

    xml_content += """  </Pages>
</ComicInfo>
"""
    return xml_content.encode('utf-8')

def create_cbz_archive(manga_title, chapter_folders, output_filename=None):
    """Creates a .cbz archive from downloaded chapter folders, including ComicInfo.xml."""
    cbz_file_name = output_filename or f"{manga_title}.cbz"
    try:
        bookmarks, current_page_index, total_images = [], 0, 0
        chapter_folders.sort(key=lambda f: int(''.join(filter(str.isdigit, (os.path.basename(f).split('-')[-1] if f else '0'))) or 0))
        
        for folder in chapter_folders:
            if folder is None or not os.path.isdir(folder): continue
            clean_chapter_title = os.path.basename(folder).replace('-', ' ').replace('_', ' ').title()
            bookmarks.append((current_page_index, clean_chapter_title))
            num_images = len(sorted([f for f in os.listdir(folder) if os.path.isfile(os.path.join(folder, f))]))
            current_page_index += num_images
        
        total_images = current_page_index
        if total_images == 0:
            print(f"Warning: No images found for {manga_title}, skipping CBZ creation.")
            return False

        comic_info_content = generate_comic_info_xml(manga_title, bookmarks, total_images)
        
        print(f"\nCreating CBZ archive: {os.path.basename(cbz_file_name)}...")
        with zipfile.ZipFile(cbz_file_name, 'w', zipfile.ZIP_DEFLATED) as zf:
            zf.writestr('ComicInfo.xml', comic_info_content)
            for folder in chapter_folders:
                images = sorted(os.listdir(folder))
                for image_name in images:
                    image_path = os.path.join(folder, image_name)
                    if os.path.isfile(image_path):
                        archive_name = f"{os.path.basename(folder)}/{image_name}"
                        zf.write(image_path, arcname=archive_name)
        return True
    except Exception as e:
        print(f"Failed to create CBZ file for {manga_title}. Reason: {e}")
        return False

# --- CORE LOGIC FUNCTIONS ---

def infer_chapter_slugs_from_zip(zf):
    """Analyzes a zip file and returns a set of chapter slugs (using folder names)."""
    chapters = {}
    image_files = [f for f in zf.namelist() if not f.startswith('__MACOSX') and not f.endswith('/') and f != 'ComicInfo.xml']
    for item_path in image_files:
        parent_dir = os.path.dirname(item_path)
        if parent_dir:
            chapters.setdefault(parent_dir, 0)
    return set(chapters.keys())

def update_local_cbz(cbz_path, custom_title=None, force_overwrite=False):
    """
    Updates a local CBZ with metadata, inferring from its internal structure
    and reorganizing flat files into a folder structure.
    """
    if not cbz_path.lower().endswith('.cbz'):
        return f"Skipping non-CBZ file: {cbz_path}"
    if not zipfile.is_zipfile(cbz_path):
        return f"Error: {os.path.basename(cbz_path)} is not a valid zip file."
    
    temp_cbz_path = cbz_path + ".tmp"
    try:
        chapters = {}
        with zipfile.ZipFile(cbz_path, 'r') as zf:
            if 'ComicInfo.xml' in zf.namelist() and not force_overwrite:
                return "Skipping: ComicInfo.xml already exists."
            
            image_files = sorted([f for f in zf.namelist() if not f.startswith('__MACOSX') and not f.endswith('/') and f != 'ComicInfo.xml'])
            if not image_files:
                return "Skipping: No image files found."

            parent_dirs = {os.path.dirname(f) for f in image_files}
            if len(parent_dirs) == 1 and '' in parent_dirs:
                chapter_regex = re.compile(r'([cv]|ch|chapter)\s?(\d+)', re.IGNORECASE)
                for item_path in image_files:
                    match = chapter_regex.search(os.path.basename(item_path))
                    chapter_num = int(match.group(2)) if match else 0
                    chapter_key = f"Chapter {chapter_num:03d}"
                    chapters.setdefault(chapter_key, []).append(item_path)
                if len(chapters) == 1 and "Chapter 000" in chapters:
                    return "Skipping: Could not infer chapters from filenames."
            else:
                for item_path in image_files:
                    parent_dir = os.path.dirname(item_path)
                    chapters.setdefault(parent_dir, []).append(item_path)

        bookmarks, current_page_index, total_images = [], 0, 0
        sorted_chapter_keys = sorted(chapters.keys())

        for chapter_key in sorted_chapter_keys:
            clean_chapter_title = chapter_key.replace('-', ' ').replace('_', ' ').title()
            bookmarks.append((current_page_index, clean_chapter_title))
            num_pages_in_chapter = len(chapters[chapter_key])
            current_page_index += num_pages_in_chapter
            total_images += num_pages_in_chapter

        if total_images == 0:
            return "Skipping: No images found after analysis."
            
        manga_title = custom_title or os.path.splitext(os.path.basename(cbz_path))[0].replace('-', ' ').title()
        comic_info_content = generate_comic_info_xml(manga_title, bookmarks, total_images)
        
        with zipfile.ZipFile(cbz_path, 'r') as original_zf:
            with zipfile.ZipFile(temp_cbz_path, 'w', zipfile.ZIP_DEFLATED) as temp_zf:
                temp_zf.writestr('ComicInfo.xml', comic_info_content)
                for chapter_folder, image_list in chapters.items():
                    for original_image_path in image_list:
                        new_image_path = f"{chapter_folder}/{os.path.basename(original_image_path)}"
                        temp_zf.writestr(new_image_path, original_zf.read(original_image_path))

        shutil.move(temp_cbz_path, cbz_path)
        return f"Success: Reorganized and added metadata for {total_images} pages into {len(bookmarks)} chapters."

    except Exception as e:
        if os.path.exists(temp_cbz_path):
            os.remove(temp_cbz_path)
        return f"Error: {e}"

def sync_cbz_with_source(cbz_path, series_url, args):
    """Synchronizes a local CBZ file with an online source, downloading missing chapters concurrently."""
    print(f"--- Starting Sync for: {os.path.basename(cbz_path)} ---")
    print(f"Source URL: {series_url}")
    if not os.path.exists(cbz_path):
        print(f"Error: Local file not found at {cbz_path}"); return
    with zipfile.ZipFile(cbz_path, 'r') as zf:
        local_slugs = infer_chapter_slugs_from_zip(zf)
    print(f"Found {len(local_slugs)} chapters locally.")
    online_urls = find_all_chapter_urls(series_url)
    if not online_urls:
        print("Could not retrieve chapter list from source. Aborting sync."); return
    if args.exclude:
        online_urls = [u for u in online_urls if u.strip('/').split('/')[-1] not in args.exclude]
    slug_to_url = {url.strip('/').split('/')[-1]: url for url in online_urls}
    online_slugs = set(slug_to_url.keys())
    missing_slugs = sorted(list(online_slugs - local_slugs))
    if not missing_slugs:
        print("CBZ is already up-to-date. No new chapters found."); return
    print(f"Found {len(missing_slugs)} new chapters to download: {', '.join(missing_slugs)}")
    urls_to_download = [slug_to_url[slug] for slug in missing_slugs]
    with tempfile.TemporaryDirectory() as temp_dir:
        print(f"\nDownloading new chapters using up to {args.chapter_workers} parallel workers...")
        downloaded_chapter_paths = []
        headers = {'User-Agent': 'Mozilla/5.0'}
        with requests.Session() as session:
            session.headers.update(headers)
            with concurrent.futures.ThreadPoolExecutor(max_workers=args.chapter_workers) as executor:
                future_to_url = {executor.submit(download_chapter_images, session, url, temp_dir, args.workers): url for url in urls_to_download}
                pbar = tqdm(concurrent.futures.as_completed(future_to_url), total=len(urls_to_download), desc="Downloading Chapters")
                for future in pbar:
                    chapter_path = future.result()
                    if chapter_path: downloaded_chapter_paths.append(chapter_path)
        print("\nExtracting existing chapters...")
        with zipfile.ZipFile(cbz_path, 'r') as zf:
            zf.extractall(path=temp_dir)
        existing_chapter_folders = [os.path.join(temp_dir, d) for d in os.listdir(temp_dir) if os.path.isdir(os.path.join(temp_dir, d))]
        all_chapter_folders = existing_chapter_folders + downloaded_chapter_paths
        manga_title = os.path.splitext(os.path.basename(cbz_path))[0]
        if create_cbz_archive(manga_title, all_chapter_folders, output_filename=cbz_path):
            print(f"\n--- Sync complete for: {os.path.basename(cbz_path)} ---")
        else:
            print(f"\n--- Sync failed for: {os.path.basename(cbz_path)} ---")

# --- WEB SCRAPING FUNCTIONS ---
def find_all_chapter_urls(main_manga_url):
    print(f"Searching for all chapter links on: {main_manga_url}")
    try:
        headers = {'User-Agent': 'Mozilla/5.0'}
        with requests.get(main_manga_url, headers=headers) as response:
            response.raise_for_status()
            soup = BeautifulSoup(response.content, 'html.parser')
            chapter_list_items = soup.find_all('li', class_='wp-manga-chapter')
        if not chapter_list_items: return []
        all_chapter_urls = [urljoin(main_manga_url, item.find('a')['href']) for item in chapter_list_items if item.find('a')]
        all_chapter_urls.reverse()
        return all_chapter_urls
    except requests.exceptions.RequestException as e:
        print(f"Error accessing the main manga page: {e}"); return []

def download_image(session, img_url, file_path):
    if os.path.exists(file_path): return "skipped"
    try:
        with session.get(img_url, stream=True) as r:
            r.raise_for_status()
            with open(file_path, 'wb') as f: shutil.copyfileobj(r.raw, f)
        return "downloaded"
    except requests.exceptions.RequestException: return "failed"

def download_chapter_images(session, chapter_url, base_download_dir, max_workers):
    try:
        chapter_name = chapter_url.strip('/').split('/')[-1]
        output_dir = os.path.join(base_download_dir, chapter_name)
        os.makedirs(output_dir, exist_ok=True)
        with session.get(chapter_url) as response:
            response.raise_for_status()
            soup = BeautifulSoup(response.content, 'html.parser')
            image_tags = soup.find_all('img', class_='wp-manga-chapter-img')
        if not image_tags: return None
        tasks = [(urljoin(chapter_url, img.get('src','').strip()), os.path.join(output_dir, f"page_{i+1:03d}.jpg")) for i, img in enumerate(image_tags) if img.get('src','').strip()]
        with concurrent.futures.ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_url = {executor.submit(download_image, session, url, path): url for url, path in tasks}
            pbar = tqdm(concurrent.futures.as_completed(future_to_url), total=len(tasks), desc=f"Images for {chapter_name}", leave=False)
            for future in pbar:
                if future.result() == "failed": pbar.write(f"  - Failed to download image: {future_to_url[future]}")
        return output_dir
    except requests.exceptions.RequestException as e:
        print(f"Failed to process chapter {chapter_url}. Reason: {e}"); return None

# --- MAIN EXECUTION LOGIC ---
def main():
    parser = argparse.ArgumentParser(
        description="A tool to download manga, add metadata to local CBZ files, or update them with new chapters.",
        formatter_class=argparse.RawTextHelpFormatter,
        epilog="""Examples:
  (1) Download a new series:
      python %(prog)s "URL_of_manga_series"

  (2) Update a local file with missing chapters (using 4 chapter workers):
      python %(prog)s "URL_of_manga_series" --update "file.cbz" --chapter-workers 4

  (3) Add compatible metadata to all CBZ files in a folder, faster:
      python %(prog)s "*.cbz" --force --batch-workers 8
"""
    )
    parser.add_argument("source", help="The source URL, a path to a single .cbz file, or a glob pattern (e.g., \"*.cbz\").")
    parser.add_argument("--update", help="Path to a local CBZ file to update with missing chapters from the source URL.")
    parser.add_argument("-t", "--title", help="Custom title for the output.")
    parser.add_argument("-e", "--exclude", action="append", help="A chapter URL slug to exclude. Can be used multiple times.")
    parser.add_argument("-f", "--force", action="store_true", help="Force overwrite of existing ComicInfo.xml in metadata-only mode.")
    parser.add_argument("-w", "--workers", type=int, default=10, help="Number of concurrent image download threads per chapter.")
    parser.add_argument("--chapter-workers", type=int, default=4, help="Number of concurrent chapters to download during an update.")
    parser.add_argument("--batch-workers", type=int, default=4, help="Number of local files to process concurrently in batch mode.")
    
    args = parser.parse_args()
    
    if args.update and args.source.lower().startswith(('http://', 'https://')):
        sync_cbz_with_source(args.update, args.source, args)
    
    elif '*' in args.source or '?' in args.source:
        print(f"Batch metadata/reorganization mode activated. Searching for files matching: {args.source}")
        file_list = glob.glob(args.source, recursive=True)
        if not file_list: print("No files found matching pattern."); return
        
        print(f"Found {len(file_list)} files to process using up to {args.batch_workers} parallel workers.")
        if args.title: print("Warning: --title argument is ignored in batch mode.")
        
        with concurrent.futures.ThreadPoolExecutor(max_workers=args.batch_workers) as executor:
            future_to_file = {executor.submit(update_local_cbz, file_path, None, args.force): file_path for file_path in file_list}
            pbar = tqdm(concurrent.futures.as_completed(future_to_file), total=len(file_list), desc="Processing local files")
            for future in pbar:
                file_name = os.path.basename(future_to_file[future])
                pbar.set_description(f"Processing {file_name}")
                result = future.result()
                if "Skipping" in result or "Error" in result:
                    pbar.write(f"{file_name}: {result}")
        print("\nBatch processing complete.")

    elif os.path.exists(args.source):
        result = update_local_cbz(args.source, args.title, args.force)
        print(result)

    elif args.source.lower().startswith(('http://', 'https://')):
        input_url = args.source
        is_single_chapter = "chapter-" in input_url.split('/')[-2] or "chapter-" in input_url.split('/')[-1]
        manga_title = args.title or (input_url.split('/manga/')[-1].strip('/').split('/chapter-')[0].replace('-', ' ').title())
        
        chapter_urls = [input_url] if is_single_chapter else find_all_chapter_urls(input_url)
        if args.exclude:
            original_count = len(chapter_urls)
            chapter_urls = [u for u in chapter_urls if u.strip('/').split('/')[-1] not in args.exclude]
            if len(chapter_urls) < original_count: print(f"Excluded {original_count - len(chapter_urls)} chapter(s). New count: {len(chapter_urls)}")
        
        if chapter_urls:
            with tempfile.TemporaryDirectory() as temp_dir:
                download_base_dir = os.path.join(temp_dir, "manga_download")
                os.makedirs(download_base_dir, exist_ok=True)
                downloaded_chapter_paths, headers = [], {'User-Agent': 'Mozilla/5.0'}
                with requests.Session() as session:
                    session.headers.update(headers)
                    with concurrent.futures.ThreadPoolExecutor(max_workers=args.chapter_workers) as executor:
                         future_to_url = {executor.submit(download_chapter_images, session, url, download_base_dir, args.workers): url for url in chapter_urls}
                         pbar = tqdm(concurrent.futures.as_completed(future_to_url), total=len(chapter_urls), desc="Downloading All Chapters")
                         for future in pbar:
                             path = future.result()
                             if path: downloaded_chapter_paths.append(path)
                if downloaded_chapter_paths:
                    final_title = manga_title
                    if is_single_chapter and not args.title: final_title = input_url.strip('/').split('/')[-1]
                    if create_cbz_archive(final_title, downloaded_chapter_paths):
                         print("\n\nAll tasks complete.")
                else:
                    print("\nNo chapters were downloaded.")
        else:
            print("\nNo chapters were found or an error occurred. Exiting.")
    else:
        print(f"Error: Source '{args.source}' is not a valid URL, an existing file path, or a recognized file pattern.")

if __name__ == "__main__":
    main()
