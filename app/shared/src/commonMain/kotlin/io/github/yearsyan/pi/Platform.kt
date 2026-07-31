package io.github.yearsyan.pi

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform