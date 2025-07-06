package com.mangacombiner

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MangaCombinerApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<MangaCombinerApplication>(*args)
}
