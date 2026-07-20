package me.ramos.unigate

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class UnigateApplication

fun main(args: Array<String>) {
  runApplication<UnigateApplication>(*args)
}
