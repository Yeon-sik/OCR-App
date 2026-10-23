package com.pricetrace.receiptocr.pricetrace
import com.pricetrace.receiptocr.pricetrace.*
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class MultiCandidateAuthorityTest {
 @Test fun unchangedLegacyCandidateSurvivesSiblingEdit()=checkPreservation(false)
 @Test fun unchangedRevisionCandidateSurvivesSiblingEdit()=checkPreservation(true)
 private fun checkPreservation(modern:Boolean)=runTest {
  val a=ProductCandidate("legacy-a","Product A",brand="Raw A",evidence=listOf(ProductCandidateEvidence(sourceType="user_statement",sourceRef="user-statement:product",field="product_name",observedValue="Product A")))
  val b=a.copy(clientKey="legacy-b",productName="Product B")
  val envelope=YeonsikOcrEnvelope(IngestionMode.PACKAGED_PRODUCT,IngestionSource("fixture",emptyList(),userText="Two explicitly reviewed products"),productCandidates=listOf(a,b),schemaVersion=YEONSIK_OCR_V3_SCHEMA,targets=setOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE))
  val store=InMemoryIngestionSessionStore();val o=IngestionOrchestrator(store,submitters=emptyMap());o.start("i","doc",envelope)
  val s=requireNotNull(store.get("i"));val legacy="""{"responses":[{"clientKey":"legacy-a","candidateId":"00000000-0000-4000-8000-000000000001"},{"clientKey":"legacy-b","candidateId":"00000000-0000-4000-8000-000000000002"}]}"""
  store.save(s.copy(projections=s.projections.map { if(it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE) it.copy(status=ProjectionStatus.UPLOADED,idempotencyKey="old-accepted",metadataJson=legacy) else it }))
  if(modern) {
   val meta=buildJsonObject { put("responses",JsonArray(listOf(a,b).map { c -> buildJsonObject {
    put("clientKey",c.clientKey);put("candidateId","00000000-0000-4000-8000-000000000001")
    put("priceTraceAuthorityClientKey",PriceTraceCandidatePayload.revision(c).authorityClientKey)
    put("sourceRevisionFingerprint",PriceTraceCandidatePayload.revision(c).sourceRevisionFingerprint)
   } })) }.toString()
   val current=requireNotNull(store.get("i"));store.save(current.copy(projections=current.projections.map { if(it.projection==IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE) it.copy(metadataJson=meta) else it }))
  }
  val expectedA=if(modern) PriceTraceCandidatePayload.revision(a).authorityClientKey else a.clientKey
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
  val calls=mutableListOf<PriceObservationHttpRequest>();var fail=true
  val config=object:PriceTraceSupabaseStore {
   override fun read()=PriceTraceSupabaseConfig("https://pt.example.invalid","sb_publishable_fixture_key_123","user",accessToken="token")
   override fun saveConnection(url:String,publishableKey:String)=Result.success(read())
   override fun saveSession(userId:String,email:String,accessToken:String,refreshToken:String)=Result.success(read())
   override fun clearSession()=true
  }
  val gateway=PriceTraceCanonicalGateway(config,PriceObservationHttpTransport { r ->
   calls+=r;val sent=Json.parseToJsonElement(r.body!!).jsonObject["p_candidate"]!!.jsonObject
   if(sent["product_name"]!!.jsonPrimitive.content=="Product B" && fail) { fail=false;PriceObservationHttpResponse(503,"temporary") }
   else PriceObservationHttpResponse(200,buildJsonObject { put("clientKey",sent.getValue("client_key"));put("candidateId","00000000-0000-4000-8000-000000000001") }.toString())
  })
  val submitter=PriceTraceProductCandidateProjectionSubmitter(gateway)
  fun request(previous:String?)=ProjectionRequest("i",IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,"source","new-projection-key",envelope=edited,previousMetadataJson=previous)
  val failed=submitter.submit(request(state.metadataJson)) as ProjectionSubmission.Failure
  val success=submitter.submit(request(failed.metadataJson)) as ProjectionSubmission.Success
  val keys=calls.map { Json.parseToJsonElement(it.body!!).jsonObject["p_candidate"]!!.jsonObject["client_key"]!!.jsonPrimitive.content }
  val expectedB=PriceTraceCandidatePayload.revision(edited.productCandidates[1]).authorityClientKey
  assertEquals(listOf(expectedA,expectedB,expectedA,expectedB),keys)
  assertEquals(mapOf(a.clientKey to expectedA,b.clientKey to expectedB),PriceTraceCandidatePayload.acceptedKeys(success.metadataJson,edited.productCandidates))
  checkDownstream(edited, requireNotNull(success.metadataJson), config, listOf(expectedA, expectedB))
 }

 private suspend fun checkDownstream(edited: YeonsikOcrEnvelope, metadata: String,
     config: PriceTraceSupabaseStore, expectedKeys: List<String>) {
  val calls = mutableListOf<PriceObservationHttpRequest>()
  val selected = mutableMapOf<String, JsonObject>()
  val brandId = "00000000-0000-4000-8000-000000000003"
  val gateway = PriceTraceCanonicalGateway(config, PriceObservationHttpTransport { request ->
   calls += request
   val body = Json.parseToJsonElement(request.body!!).jsonObject
   when {
    request.url.endsWith("submit_product_candidate_v1") -> PriceObservationHttpResponse(200, buildJsonObject {
     put("clientKey", body.getValue("p_candidate").jsonObject.getValue("client_key"))
     put("candidateId", "00000000-0000-4000-8000-000000000001")
    }.toString())
    request.url.endsWith("set_product_candidate_brand_resolution_v2") -> {
     val key = body.getValue("p_client_key").jsonPrimitive.content
     selected[key] = body
     PriceObservationHttpResponse(200, buildJsonObject {
      put("product_client_key", key); put("request_fingerprint", "a".repeat(64))
      put("source_field", body.getValue("p_source_field")); put("observed_value", body.getValue("p_observed_value"))
      put("brand_id", body.getValue("p_brand_id")); put("cleared", false)
     }.toString())
    }
    request.url.endsWith("get_product_candidate_brand_resolutions_v2") -> {
     val key = body.getValue("p_client_key").jsonPrimitive.content
     val mutation = selected.getValue(key)
     PriceObservationHttpResponse(200, JsonArray(listOf(buildJsonObject {
      put("product_client_key", key); put("request_fingerprint", "a".repeat(64))
      put("source_field", "brand"); put("observed_value", mutation.getValue("p_observed_value"))
      put("brand_id", brandId); put("canonical_name", "Canonical"); put("brand_active", true)
      put("parent_brand_id", JsonNull); put("parent_canonical_name", JsonNull)
      put("created_at", "2026-10-24T00:00:00Z"); put("updated_at", "2026-10-24T00:00:00Z")
      put("pair_relationship", "single"); put("pair_relationship_valid", true); put("requires_review", false)
     })).toString())
    }
    "p_observation" in body -> PriceObservationHttpResponse(200, """{"observationId":"observation"}""")
    "p_purchase" in body -> PriceObservationHttpResponse(200, """{"purchaseSourceId":"source","observationCreated":false,"observationIds":[]}""")
    else -> error("Unexpected RPC: ${request.url}")
   }
  })
  val drafts = edited.productCandidates.map { ProductBrandResolutionDraft(it.clientKey,
      ProductBrandFactField.BRAND, requireNotNull(it.brand), ProductBrandSelectionIntent.SELECTED, brandId, "Canonical") }
  val brandResult = PriceTraceProductCandidateProjectionSubmitter(gateway).submit(ProjectionRequest("i",
      IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE, "source", "brands", envelope=edited,
      previousMetadataJson=metadata, brandResolutionDrafts=drafts))
  assertTrue(brandResult.toString(), brandResult is ProjectionSubmission.Success)
  for (rpc in listOf("set_product_candidate_brand_resolution_v2", "get_product_candidate_brand_resolutions_v2")) {
   assertEquals(expectedKeys, calls.filter { it.url.endsWith(rpc) }.map {
    Json.parseToJsonElement(it.body!!).jsonObject.getValue("p_client_key").jsonPrimitive.content
   })
  }
  fun fixture(name: String) = java.io.File("../examples", name).readText()
  val ready = IngestionReview(IngestionReviewStatus.READY, verificationBasis=VerificationBasis.MANUAL_CANONICAL_REVIEW)
  val base = YeonsikOcrV3Json.decode(fixture("yeonsik-ocr.v3.text-only-retail.example.json"), "mixed")
  val standalone = base.copy(productCandidates=edited.productCandidates,
      source=base.source.copy(sourceFiles=edited.source.sourceFiles), review=ready,
      priceObservations=edited.productCandidates.map { candidate -> base.priceObservations.single().copy(
          clientKey="price-${candidate.clientKey}", productClientKey=candidate.clientKey) })
  val purchaseBase = YeonsikOcrV4Json.decode(fixture("yeonsik-ocr.v4.purchase.example.json"), "mixed")
  val purchase = purchaseBase.copy(productCandidates=edited.productCandidates,
      source=purchaseBase.source.copy(sourceFiles=purchaseBase.source.sourceFiles + edited.source.sourceFiles, userText=edited.source.userText), review=ready,
      purchaseRecords=edited.productCandidates.map { candidate -> purchaseBase.purchaseRecords.single().copy(
          clientKey="purchase-${candidate.clientKey}", lineItems=listOf(purchaseBase.purchaseRecords.single().lineItems.single()
              .copy(productClientKey=candidate.clientKey, description=candidate.productName))) })
  for (envelope in listOf(standalone, purchase)) {
   val canonicalBefore = YeonsikOcrEnvelopeCodec.encode(envelope)
   val result = PriceTraceCanonicalProjectionSubmitter(gateway).submit(ProjectionRequest("i",
       IngestionProjection.PRICETRACE_PRICE_OBSERVATION, "source", "downstream", envelope=envelope,
       dependencyMetadataJson=mapOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE to metadata)))
   assertTrue(result.toString(), result is ProjectionSubmission.Success)
   assertEquals(canonicalBefore, YeonsikOcrEnvelopeCodec.encode(envelope))
  }
  val bodies = calls.map { Json.parseToJsonElement(it.body!!).jsonObject }
  assertEquals(expectedKeys, bodies.mapNotNull { it["p_observation"]?.jsonObject?.get("product")?.jsonObject
      ?.get("product_client_key")?.jsonPrimitive?.content })
  assertEquals(expectedKeys, bodies.mapNotNull { it["p_purchase"]?.jsonObject }.flatMap {
   it.getValue("items").jsonArray.map { item -> item.jsonObject.getValue("product").jsonObject.getValue("client_key").jsonPrimitive.content }
  })
 }
}
