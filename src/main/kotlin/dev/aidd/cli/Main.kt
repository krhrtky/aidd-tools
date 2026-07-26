package dev.aidd.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(AiddCli().execute(args.toList()))
}

