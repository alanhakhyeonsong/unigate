package me.ramos.downstream

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class DownstreamDemoApplication

fun main(args: Array<String>) {
    runApplication<DownstreamDemoApplication>(*args)
}
