package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptocr.pricetrace.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SavedIdentityConnectionsTest {
    private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12,'0')}"
    private fun inventory(owner: String = id(11), selected: Boolean = false, entity: String = "restaurant") = """{"schemaVersion":"identity-connections.v1","ownerId":"$owner","truncated":false,"sources":[{"entity":"$entity","sourceId":"${id(1)}","name":"한성대학교 학식당","branchName":null,"context":null,"selected":${if(selected) """{"id":"${id(2)}","name":"한성대학교 학생식당"}""" else "null"}}]}"""
    private val command get() = SavedIdentityCommand(SavedIdentityAction.SEARCH,"restaurant",id(1),"한성대학교 학식당")
    private fun search(source: String = id(1), entity: String = "restaurant") = """{"schemaVersion":"identity-search.v1","entity":"$entity","sourceId":"$source","query":"한성대학교 학식당","choices":[{"id":"${id(2)}","name":"한성대학교 학생식당","branchName":null,"context":null,"similarity":0.8889,"matchKind":"similar"}]}"""

    @Test fun searchDoesNotWriteAndConnectSendsExplicitTargetConfirmation() = runTest {
        val calls = mutableListOf<PriceObservationHttpRequest>()
        var connected = false
        val gateway = PriceTraceCanonicalGateway(FakeStore(), PriceObservationHttpTransport { request ->
            calls += request
            val response = when(request.url.substringAfterLast('/')) {
                "get_my_identity_connection_sources_v1" -> inventory(selected = connected)
                "search_my_identity_connections_v1" -> search()
                "connect_my_identity_v1" -> { connected = true; inventory(selected = true) }
                else -> error("unexpected endpoint")
            }
            PriceObservationHttpResponse(200,response)
        })
        val result = gateway.reviewSavedIdentity(SavedIdentityRequest(command,listOf(id(1)))) as PriceTraceCanonicalOutcome.Success
        assertEquals("이름 유사도 89%",SavedIdentityConnections.decode(result.response).choices.single().matchLabel)
        assertFalse(connected)
        val connectedResult = gateway.reviewSavedIdentity(SavedIdentityRequest(command.copy(action=SavedIdentityAction.CONNECT,targetId=id(2)),listOf(id(1)))) as PriceTraceCanonicalOutcome.Success
        assertEquals(id(2),SavedIdentityConnections.decode(connectedResult.response).sources.single().selected?.id)
        val body = Json.parseToJsonElement(calls.single { it.url.endsWith("connect_my_identity_v1") }.body!!).jsonObject
        assertEquals(JsonPrimitive(true),body["p_user_verified"])
        assertEquals(JsonPrimitive(id(2)),body["p_target_id"])
        assertTrue(calls.all { it.url.contains("pt.example") })
    }

    @Test fun differentOwnerAndMismatchedSearchAreRejectedBeforeAnyMutation() = runTest {
        for (wrongOwner in listOf(true,false)) {
            var calls = 0
            val gateway = PriceTraceCanonicalGateway(FakeStore(),PriceObservationHttpTransport { request ->
                calls++
                PriceObservationHttpResponse(200,if(request.url.endsWith("get_my_identity_connection_sources_v1"))
                    inventory(owner=if(wrongOwner) id(22) else id(11)) else search(id(99)))
            })
            assertTrue(gateway.reviewSavedIdentity(SavedIdentityRequest(command,listOf(id(1)))) is PriceTraceCanonicalOutcome.Failure)
            assertEquals(if(wrongOwner) 1 else 2,calls)
        }
    }

    @Test fun switchingAccountDuringReadRejectsCachedOwnerResponse() = runTest {
        val store = FakeStore()
        val gateway = PriceTraceCanonicalGateway(store,PriceObservationHttpTransport {
            store.owner = id(22)
            PriceObservationHttpResponse(200,inventory())
        })
        assertTrue(gateway.reviewSavedIdentity(SavedIdentityRequest(SavedIdentityCommand(),listOf(id(1)))) is PriceTraceCanonicalOutcome.Failure)
    }

    @Test fun malformedCandidateCannotBecomeAnIdentity() {
        val valid = """{"inventory":${inventory()},"search":${search()}}"""
        assertTrue(runCatching { SavedIdentityConnections.decode(Json.parseToJsonElement(valid.replace("0.8889","1.2")).jsonObject) }.isFailure)
        assertTrue(runCatching { SavedIdentityConnections.decode(Json.parseToJsonElement(valid.replace(id(2),"guessed-name")).jsonObject) }.isFailure)
    }

    @Test fun largeSavedRecordListsRemainSafeToInspectBeforeRequestValidation() {
        val metadata = buildJsonObject {
            put("sources", JsonArray((1..501).map { buildJsonObject { put("purchaseSourceId", id(it)) } }))
        }.toString()
        val session = IngestionSession("large", "doc", "canonical", "fingerprint", IngestionReviewStatus.READY, "now", "now",
            listOf(ProjectionState(IngestionProjection.PRICETRACE_PRICE_OBSERVATION, updatedAt = "now", metadataJson = metadata)))
        assertEquals(501, SavedIdentityConnections.recordIds(session).size)
    }

    @Test fun connectRejectsRefreshedInventoryThatLosesTheConfirmedSelection() = runTest {
        var writes = 0
        val gateway = PriceTraceCanonicalGateway(FakeStore(), PriceObservationHttpTransport { request ->
            val response = if (request.url.endsWith("connect_my_identity_v1")) {
                writes++
                inventory(selected = true)
            } else inventory()
            PriceObservationHttpResponse(200, response)
        })
        val result = gateway.reviewSavedIdentity(SavedIdentityRequest(
            command.copy(action = SavedIdentityAction.CONNECT, targetId = id(2)), listOf(id(1))))
        assertTrue(result.toString(), result is PriceTraceCanonicalOutcome.Failure)
        assertEquals(1, writes)
    }

    @Test fun unavailableServerAndInvalidSelectorsFailWithoutConnectionWrites() = runTest {
        val calls = mutableListOf<PriceObservationHttpRequest>()
        val gateway = PriceTraceCanonicalGateway(FakeStore(), PriceObservationHttpTransport { request ->
            calls += request
            PriceObservationHttpResponse(404, "{}")
        })
        for (selectors in listOf(emptyList(), listOf("guessed-name"), (1..501).map(::id))) {
            assertTrue(gateway.reviewSavedIdentity(SavedIdentityRequest(SavedIdentityCommand(), selectors)) is PriceTraceCanonicalOutcome.Failure)
        }
        assertTrue(calls.isEmpty())
        val unavailable = gateway.reviewSavedIdentity(SavedIdentityRequest(SavedIdentityCommand(), listOf(id(1)))) as PriceTraceCanonicalOutcome.Failure
        assertEquals("서버에 연결 기능이 아직 적용되지 않았습니다.", unavailable.message)
        assertTrue(calls.single().url.endsWith("get_my_identity_connection_sources_v1"))
    }

    @Test fun recordSelectorsAreDeduplicatedAndNeverReadFromConnectionCache() {
        val metadata = """{"receiptId":"${id(1)}","storeId":"${id(2)}","sources":[{"purchaseSourceId":"${id(1)}"},{"candidateId":"${id(3)}"}],"identityConnections":{"storeId":"${id(99)}"}}"""
        val session = IngestionSession("selectors", "doc", "canonical", "fingerprint", IngestionReviewStatus.READY, "now", "now",
            listOf(ProjectionState(IngestionProjection.PRICETRACE_RECEIPT, updatedAt = "now", metadataJson = metadata)))
        assertEquals(listOf(id(1), id(2), id(3)), SavedIdentityConnections.recordIds(session))
    }

    @Test fun allSupportedEntitiesUseOwnerScopedRpcParametersAndAuthentication() = runTest {
        for (entity in listOf("brand", "store", "restaurant")) {
            val calls = mutableListOf<PriceObservationHttpRequest>()
            val gateway = PriceTraceCanonicalGateway(FakeStore(), PriceObservationHttpTransport { request ->
                calls += request
                PriceObservationHttpResponse(200, if (request.url.endsWith("get_my_identity_connection_sources_v1"))
                    inventory(entity = entity) else search(entity = entity))
            })
            val result = gateway.reviewSavedIdentity(SavedIdentityRequest(command.copy(entity = entity), listOf(id(1)))) as PriceTraceCanonicalOutcome.Success
            assertEquals(entity, SavedIdentityConnections.decode(result.response).sources.single().entity)
            assertEquals(2, calls.size)
            assertTrue(calls.all { it.method == "POST" && it.headers["Authorization"] == "Bearer token" })
            val selectors = Json.parseToJsonElement(calls.first().body!!).jsonObject
            assertEquals(JsonArray(listOf(JsonPrimitive(id(1)))), selectors["p_record_ids"])
            val body = Json.parseToJsonElement(calls.last().body!!).jsonObject
            assertEquals(JsonPrimitive(entity), body["p_entity"])
            assertEquals(JsonPrimitive(id(1)), body["p_source_id"])
            assertEquals(JsonPrimitive(command.query), body["p_query"])
            assertEquals(setOf("p_entity", "p_source_id", "p_query"), body.keys)
        }
    }

    @Test fun sharedUseCasePersistsRuntimeChoiceWithoutSubmittingOtherAppsOrChangingFacts() = runTest {
        val raw = File("../examples/yeonsik-ocr.v5.restaurant-purchase.example.json").readText()
        val envelope = YeonsikOcrEnvelopeCodec.decode(raw,"identity-runtime")
        val evidence = envelope.source.sourceFiles.map { LocalEvidence(it.id,it.type,true) }
        val store = InMemoryIngestionSessionStore()
        var writes = 0
        val submitter = object : IngestionProjectionSubmitter, SavedIdentitySubmitter {
            override suspend fun submit(request: ProjectionRequest): ProjectionSubmission = error("identity action must not submit any projection")
            override suspend fun reviewSavedIdentity(request: SavedIdentityRequest): ProjectionSubmission {
                assertEquals(listOf(id(1)),request.recordIds)
                val response = if(request.command.action == SavedIdentityAction.SEARCH)
                    """{"inventory":${inventory()},"search":${search()}}"""
                else { writes++; """{"inventory":${inventory(selected=true)}}""" }
                return ProjectionSubmission.Success(id(1),metadataJson=response)
            }
        }
        val app = CanonicalIngestionUseCase(store,IngestionProjection.entries.associateWith { submitter })
        val imported = app.importJson(raw,"identity-runtime",evidence=evidence) as CanonicalImportResult.Success
        val verified = app.confirm(imported.session.ingestionId,imported.envelope,evidence).envelope
        val session = requireNotNull(store.get(imported.session.ingestionId))
        store.save(session.copy(projections=session.projections.map { if(it.projection==IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            it.copy(metadataJson="""{"purchaseSourceId":"${id(1)}"}""") else it }))
        val before = requireNotNull(store.get(session.ingestionId))
        val canonical = YeonsikOcrEnvelopeCodec.canonicalize(verified)
        val unverified = verified.copy(review = verified.review.copy(status = IngestionReviewStatus.NEEDS_REVIEW))
        assertTrue(app.reviewSavedIdentity(session.ingestionId, unverified, command) is ProjectionSubmission.Failure)
        assertTrue(app.reviewSavedIdentity(session.ingestionId,verified,command.copy(action=SavedIdentityAction.CONNECT,targetId=id(2))) is ProjectionSubmission.Failure)
        assertEquals(0,writes)
        assertTrue(app.reviewSavedIdentity(session.ingestionId,verified,command) is ProjectionSubmission.Success)
        for (invalid in listOf(
            command.copy(action = SavedIdentityAction.CONNECT, targetId = id(99)),
            command.copy(action = SavedIdentityAction.CONNECT, targetId = id(2), query = "다른 검색"),
            command.copy(action = SavedIdentityAction.CONNECT, targetId = id(2), sourceId = id(99)),
            command.copy(action = SavedIdentityAction.CONNECT, targetId = id(2), entity = "store"),
        )) assertTrue(app.reviewSavedIdentity(session.ingestionId, verified, invalid) is ProjectionSubmission.Failure)
        assertEquals(0, writes)
        assertTrue(app.reviewSavedIdentity(session.ingestionId,verified,command.copy(action=SavedIdentityAction.CONNECT,targetId=id(2))) is ProjectionSubmission.Success)
        val after = requireNotNull(store.get(session.ingestionId))
        assertEquals(1,writes)
        assertEquals(canonical,YeonsikOcrEnvelopeCodec.canonicalize(verified))
        assertEquals(before.canonicalFingerprint, after.canonicalFingerprint)
        assertEquals(before.verifiedCanonicalFingerprint, after.verifiedCanonicalFingerprint)
        assertEquals(before.revisionSeq, after.revisionSeq)
        assertEquals(before.verifiedArtifactFingerprints, after.verifiedArtifactFingerprints)
        assertEquals(before.projections.map { it.status },after.projections.map { it.status })
        assertEquals(before.projections.filter { it.projection!=IngestionProjection.PRICETRACE_PRICE_OBSERVATION },
            after.projections.filter { it.projection!=IngestionProjection.PRICETRACE_PRICE_OBSERVATION })
        assertEquals(listOf(id(1)),SavedIdentityConnections.recordIds(after))
        assertEquals(id(2),SavedIdentityConnections.state(after)?.sources?.single()?.selected?.id)
    }

    private inner class FakeStore : PriceTraceSupabaseStore {
        var owner = id(11)
        override fun read() = PriceTraceSupabaseConfig("https://pt.example","abcdefghijklmnopqrst",owner,"","token")
        override fun saveConnection(url:String,publishableKey:String) = Result.success(read())
        override fun saveSession(userId:String,email:String,accessToken:String,refreshToken:String) = Result.success(read())
        override fun clearSession() = true
    }
}
