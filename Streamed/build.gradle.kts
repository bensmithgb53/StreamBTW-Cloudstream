
// use an integer for version numbers
version = 13


cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

     description = "Live streams from the Streamed."
    authors = listOf("Ben")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Live",
    )

    iconUrl = "https://streamed.pk/api/images/poster/fallback.webp"
}
