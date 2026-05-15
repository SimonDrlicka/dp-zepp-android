package com.example.zepp_gestures

interface ImuIngestor {
    fun ingest(parsed: List<ImuSample>): IngestResult
    fun ingestReset(parsed: List<ImuSample>): IngestResult
}
