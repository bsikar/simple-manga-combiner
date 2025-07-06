package com.mangacombiner

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class MangaCombinerApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val isCliMode = args.isNotEmpty()
    val app = SpringApplication(MangaCombinerApplication::class.java)

    if (isCliMode) {
        app.setAdditionalProfiles("cli")
    } else {
        app.setAdditionalProfiles("gui")
        app.setHeadless(false)
    }
    app.run(*args)
}
