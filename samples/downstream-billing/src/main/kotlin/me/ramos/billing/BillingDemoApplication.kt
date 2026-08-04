package me.ramos.billing

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class BillingDemoApplication

fun main(args: Array<String>) {
    runApplication<BillingDemoApplication>(*args)
}
