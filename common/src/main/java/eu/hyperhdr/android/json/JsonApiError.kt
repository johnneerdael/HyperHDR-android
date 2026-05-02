package eu.hyperhdr.android.json

class JsonApiError(message: String, val httpCode: Int) : Exception(message)
