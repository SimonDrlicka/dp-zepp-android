package com.example.zepp_gestures

/**
 * Common contract for anything that consumes packed IMU samples coming
 * from [ImuHttpServer]. Lets the same HTTP endpoints feed either the
 * regular [GestureRecognitionService] (debug / prod modes) or the
 * [TestingService] (testing mode) without the server caring which one
 * is wired up.
 */
interface ImuIngestor {
    fun ingest(parsed: List<ImuSample>): IngestResult
    fun ingestReset(parsed: List<ImuSample>): IngestResult
}
