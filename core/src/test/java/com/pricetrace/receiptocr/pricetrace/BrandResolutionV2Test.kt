package com.pricetrace.receiptocr.pricetrace
import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class BrandResolutionV2Test {
 private val id="00000000-0000-4000-8000-000000000001"
 private val child="00000000-0000-4000-8000-000000000002"
 private val p=ProductCandidate("logical","Product",brand="Raw",subBrand="Sub",evidence=listOf(ProductCandidateEvidence(sourceAttachmentIds=listOf("photo"),sourceRef="capture:p",field="product_name",observedValue="Product")))
 private val key get()=PriceTraceCandidatePayload.revision(p).authorityClientKey
 private fun row(field:String,relation:String,active:Boolean=true)=buildJsonObject {
  put("product_client_key",key);put("request_fingerprint","a".repeat(64));put("source_field",field);put("observed_value",if(field=="brand") "Raw" else "Sub")
  put("brand_id",if(relation=="same_brand" || field=="brand") id else child);put("canonical_name",if(active) JsonPrimitive("Canonical") else JsonNull)
  put("brand_active",active);put("parent_brand_id",JsonNull);put("parent_canonical_name",JsonNull)
  put("created_at","2026-10-24T00:00:00Z");put("updated_at","2026-10-24T00:00:00Z")
  put("pair_relationship",relation);put("pair_relationship_valid",relation!="hierarchy_conflict");put("requires_review",!active || relation=="hierarchy_conflict")
 }
 @Test fun allRelationshipsAndMalformedCombinations() {
  for(relation in listOf("single","same_brand","descendant","hierarchy_conflict")) {
   val rows=listOfNotNull(row("brand",relation),if(relation!="single") row("sub_brand",relation) else null)
   assertEquals(rows,PriceTraceBrandClient.parseResolutions(JsonArray(rows).toString(),key,p))
   val bad=JsonObject(rows[0]+("pair_relationship_valid" to JsonPrimitive(relation=="hierarchy_conflict")))
   assertThrows(IllegalArgumentException::class.java) { PriceTraceBrandClient.parseResolutions(JsonArray(listOf(bad)+rows.drop(1)).toString(),key,p) }
  }
  assertEquals(1,PriceTraceBrandClient.parseResolutions(JsonArray(listOf(row("brand","single",false))).toString(),key,p).size)
  val bad=JsonObject(row("brand","single",false)+("requires_review" to JsonPrimitive(false)))
  assertThrows(IllegalArgumentException::class.java) { PriceTraceBrandClient.parseResolutions(JsonArray(listOf(bad)).toString(),key,p) }
 }
 @Test fun oldWireShapeUnknownRelationAndImpossibleCardinalityAreRejected() {
  val single = row("brand", "single")
  val old = JsonObject(single - setOf("pair_relationship", "pair_relationship_valid", "requires_review"))
  val malformed = listOf(
   listOf(old),
   listOf(JsonObject(single + ("pair_relationship" to JsonPrimitive("unknown")))),
   listOf(JsonObject(single + ("pair_relationship_valid" to JsonPrimitive("true")))),
   listOf(row("brand", "same_brand")),
   listOf(row("brand", "single"), row("sub_brand", "single")),
   listOf(row("brand", "same_brand"), JsonObject(row("sub_brand", "same_brand") + ("brand_id" to JsonPrimitive(child)))),
  )
  malformed.forEach { rows -> assertThrows(IllegalArgumentException::class.java) {
   PriceTraceBrandClient.parseResolutions(JsonArray(rows).toString(), key, p)
  } }
 }
 @Test fun driftIsDurableSemanticReviewRatherThanTransportSuccess()=checkReview(true, true)
 @Test fun reviewRequiredReadPreventsCompletionAfterSuccessfulMutation()=checkReview(false, true)
 @Test fun inactiveReadPreservesHistoricalSelection()=checkReview(false, false)
 private fun checkReview(rejectMutation: Boolean, active: Boolean)=runTest {
  val config=object:PriceTraceSupabaseStore {
   override fun read()=PriceTraceSupabaseConfig("https://pt.example.invalid","sb_publishable_fixture_key_123","user",accessToken="token")
   override fun saveConnection(url:String,publishableKey:String)=Result.success(read())
   override fun saveSession(userId:String,email:String,accessToken:String,refreshToken:String)=Result.success(read())
   override fun clearSession()=true
  }
  val gateway=PriceTraceCanonicalGateway(config,PriceObservationHttpTransport { r -> when {
   r.url.endsWith("submit_product_candidate_v1") -> PriceObservationHttpResponse(200,"""{"candidateId":"$id","clientKey":"$key"}""")
   r.url.endsWith("set_product_candidate_brand_resolution_v2") -> if (rejectMutation) PriceObservationHttpResponse(400,"brand_sub_brand_hierarchy_conflict")
    else PriceObservationHttpResponse(200,"""{"product_client_key":"$key","request_fingerprint":"${"a".repeat(64)}","source_field":"brand","observed_value":"Raw","brand_id":"$id","cleared":false}""")
   r.url.endsWith("get_product_candidate_brand_resolutions_v2") -> PriceObservationHttpResponse(200,JsonArray(if (active) listOf(row("brand","hierarchy_conflict"),row("sub_brand","hierarchy_conflict")) else listOf(row("brand","single",false))).toString())
   else -> error("V1 fallback is forbidden")
  } })
  val env=YeonsikOcrEnvelope(IngestionMode.PACKAGED_PRODUCT,IngestionSource("fixture",listOf(SourceAttachment("photo",SourceAttachmentType.PRODUCT_PHOTO))),productCandidates=listOf(p),schemaVersion=YEONSIK_OCR_V3_SCHEMA)
  val draft=ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.BRAND,"Raw",ProductBrandSelectionIntent.SELECTED,id,"Snapshot")
  val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(ProjectionRequest("i",IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,"source","key",envelope=env,brandResolutionDrafts=listOf(draft))) as ProjectionSubmission.Failure
  assertFalse(result.retryable);assertTrue(result.requiresReview)
  assertTrue(productBrandReviewMessages(result.metadataJson,p.clientKey).single().contains(if (active) "계층" else "비활성"))
  assertEquals(key,PriceTraceCandidatePayload.acceptedKeys(result.metadataJson)[p.clientKey])
 }
}
