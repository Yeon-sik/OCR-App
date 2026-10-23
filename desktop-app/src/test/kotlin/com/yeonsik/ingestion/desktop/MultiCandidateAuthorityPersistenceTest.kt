package com.yeonsik.ingestion.desktop
import com.pricetrace.receiptocr.pricetrace.*
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
class MultiCandidateAuthorityPersistenceTest {
 @Test fun siblingEditSurvivesNewDesktopStore()=runBlocking {
  val a=ProductCandidate("legacy-a","Product A",brand="Raw A",evidence=listOf(ProductCandidateEvidence(sourceAttachmentIds=listOf("photo"),sourceRef="capture:photo",field="product_name",observedValue="Product A")))
  val b=a.copy(clientKey="legacy-b",productName="Product B")
  val envelope=YeonsikOcrEnvelope(IngestionMode.PACKAGED_PRODUCT,IngestionSource("fixture",listOf(SourceAttachment("photo",SourceAttachmentType.PRODUCT_PHOTO))),productCandidates=listOf(a,b),schemaVersion=YEONSIK_OCR_V3_SCHEMA,targets=setOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE))
  val directory=Files.createTempDirectory("multi-authority");val store=DesktopSessionStore(directory);val o=IngestionOrchestrator(store,submitters=emptyMap());o.start("i","doc",envelope)
  val s=requireNotNull(store.get("i"));val legacy="""{"responses":[{"clientKey":"legacy-a","candidateId":"00000000-0000-4000-8000-000000000001"},{"clientKey":"legacy-b","candidateId":"00000000-0000-4000-8000-000000000002"}]}"""
  store.save(s.copy(projections=s.projections.map { if(it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE) it.copy(status=ProjectionStatus.UPLOADED,idempotencyKey="old-accepted",metadataJson=legacy) else it }))
  val expectedA=a.clientKey
  val restored=requireNotNull(o.prepareExistingSession("i",envelope))
  assertEquals(expectedA,PriceTraceCandidatePayload.acceptedKeys(restored.projections.single{it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE}.metadataJson,listOf(a,b))[a.clientKey])
  val edited=envelope.copy(productCandidates=listOf(a,b.copy(specification="changed-spec")))
  val revised=(o.reviseCanonicalDraft("i",edited) as IngestionStartResult.Success).session
  val state=revised.projections.single{it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE}
  assertNotNull(state.metadataJson)
  val accepted=PriceTraceCandidatePayload.acceptedKeys(state.metadataJson,edited.productCandidates)
  val effective=accepted[a.clientKey]?:PriceTraceCandidatePayload.revision(a).authorityClientKey
  assertEquals(expectedA,effective)
  assertNull(accepted[b.clientKey])
  assertEquals(ProjectionStatus.PENDING,state.status)

  val restoredStore=DesktopSessionStore(directory)
  val loaded=requireNotNull(restoredStore.get("i"))
  val saved=loaded.projections.single { it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }
  assertEquals(mapOf(a.clientKey to a.clientKey),PriceTraceCandidatePayload.acceptedKeys(saved.metadataJson,edited.productCandidates))
  val fresh=IngestionOrchestrator(restoredStore,submitters=emptyMap())
  val resumed=requireNotNull(fresh.prepareExistingSession("i",edited))
  assertEquals(saved.metadataJson,resumed.projections.single { it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }.metadataJson)

  // A new gateway reads durable candidate evidence; B still derives exactly the same revision.
  val sent = mutableListOf<JsonObject>()
  val config = object : PriceTraceSupabaseStore {
   override fun read() = PriceTraceSupabaseConfig("https://pt.example.invalid", "sb_publishable_fixture_key_123", "user", accessToken="token")
   override fun saveConnection(url: String, publishableKey: String) = Result.success(read())
   override fun saveSession(userId: String, email: String, accessToken: String, refreshToken: String) = Result.success(read())
   override fun clearSession() = true
  }
  val gateway = PriceTraceCanonicalGateway(config, PriceObservationHttpTransport { request ->
   val body = Json.parseToJsonElement(request.body!!).jsonObject
   sent += body
   if ("p_candidate" in body) PriceObservationHttpResponse(200, buildJsonObject {
    put("clientKey", body.getValue("p_candidate").jsonObject.getValue("client_key"))
    put("candidateId", "00000000-0000-4000-8000-000000000001")
   }.toString()) else PriceObservationHttpResponse(200, """{"observationId":"observation"}""")
  })
  val submitted = PriceTraceProductCandidateProjectionSubmitter(gateway).submit(ProjectionRequest("i",
      IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE, "source", "after-reopen", envelope=edited,
      previousMetadataJson=saved.metadataJson)) as ProjectionSubmission.Success
  val expected = listOf(a.clientKey, PriceTraceCandidatePayload.revision(edited.productCandidates[1]).authorityClientKey)
  assertEquals(expected, sent.map { it.getValue("p_candidate").jsonObject.getValue("client_key").jsonPrimitive.content })
  restoredStore.save(resumed.copy(projections=resumed.projections.map {
   if (it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
    it.copy(status=ProjectionStatus.UPLOADED, metadataJson=submitted.metadataJson) else it
  }))
  val checkpoint = requireNotNull(restoredStore.get("i")).projections.single { it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }
  val downstream = edited.copy(review=IngestionReview(IngestionReviewStatus.READY),
      merchantCandidate=MerchantCandidate(name="Fixture store", businessKind=com.pricetrace.receiptscanner.domain.BusinessKind.RETAIL),
      priceObservations=edited.productCandidates.map { candidate -> StandalonePriceObservation(
          "price-${candidate.clientKey}", StandalonePriceObservationKind.RETAIL_PURCHASE,
          productClientKey=candidate.clientKey, observedOn="2026-10-24", quantity=StandalonePriceObservationQuantity(1.0,"each"),
          netAmountMinor=100, sourceAttachmentIds=listOf("photo"), confidence=1.0,
          evidence=listOf(StandalonePriceObservationEvidence(sourceType="product_photo", sourceAttachmentIds=listOf("photo"), field="net_amount_minor", observedValue="100"))) })
  val projected = PriceTraceCanonicalProjectionSubmitter(gateway).submit(ProjectionRequest("i",
      IngestionProjection.PRICETRACE_PRICE_OBSERVATION, "source", "downstream-after-reopen", envelope=downstream,
      dependencyMetadataJson=mapOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to requireNotNull(checkpoint.metadataJson))))
  assertTrue(projected.toString(), projected is ProjectionSubmission.Success)
  assertEquals(expected, sent.mapNotNull { it["p_observation"]?.jsonObject?.get("product")?.jsonObject?.get("product_client_key")?.jsonPrimitive?.content })

 }
}
