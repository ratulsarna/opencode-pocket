package com.ratulsarna.ocmobile

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform