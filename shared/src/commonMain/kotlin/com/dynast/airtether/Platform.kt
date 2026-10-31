package com.dynast.airtether

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform