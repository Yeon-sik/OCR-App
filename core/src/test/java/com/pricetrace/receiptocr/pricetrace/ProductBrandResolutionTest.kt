package com.pricetrace.receiptocr.pricetrace

import com.pricetrace.receiptscanner.ingestion.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductBrandResolutionTest {
    private val root="00000000-0000-4000-8000-000000000001"
    private val child="00000000-0000-4000-8000-000000000002"
    private fun candidate()=ProductCandidate("logical-product","Observed drink",brand="Observed A",subBrand="Observed B",
        evidence=listOf(ProductCandidateEvidence(sourceAttachmentIds=listOf("photo"),sourceRef="capture:photo",field="product_name",observedValue="Observed drink")))
    private fun envelope(p: ProductCandidate)=YeonsikOcrEnvelope(IngestionMode.PACKAGED_PRODUCT,
        IngestionSource("fixture",listOf(SourceAttachment("photo",SourceAttachmentType.PRODUCT_PHOTO))),
        productCandidates=listOf(p),schemaVersion=YEONSIK_OCR_V3_SCHEMA,targets=setOf(IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE))
    private fun request(p:ProductCandidate,drafts:List<ProductBrandResolutionDraft> = emptyList(),previous:String?=null)=ProjectionRequest(
        "ingestion",IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE,"source-json-unchanged","projection-key",envelope=envelope(p),
        brandResolutionDrafts=drafts,previousMetadataJson=previous)
    private class Store(private val signed:Boolean=true):PriceTraceSupabaseStore {
        override fun read()=PriceTraceSupabaseConfig("https://pt.example.invalid","sb_publishable_fixture_key_123",if(signed) "user" else "",accessToken=if(signed) "token" else "")
        override fun saveConnection(url:String,publishableKey:String)=Result.success(read())
        override fun saveSession(userId:String,email:String,accessToken:String,refreshToken:String)=Result.success(read())
        override fun clearSession()=true
    }
    @Test fun revisionKeyTracksExactPtSourceButDoesNotChangeLogicalKey() {
        val p=candidate(); val before=YeonsikOcrEnvelopeCodec.encode(envelope(p)); val revision=PriceTraceCandidatePayload.revision(p)
        assertEquals(revision,PriceTraceCandidatePayload.revision(p.copy(confidence=0.5)))
        assertTrue(revision.authorityClientKey.matches(Regex("ocrpc-r1-[0-9a-f]{64}")))
        assertFalse(PriceTraceCandidatePayload.source(p).containsKey("client_key"))
        listOf(p.copy(brand="edited"),p.copy(subBrand="edited"),p.copy(productName="edited"),p.copy(specification="500ml"),
            p.copy(manufacturer="maker"),p.copy(sourceVersion="revision-b"),p.copy(clientKey="another-logical"),
            p.copy(evidence=p.evidence.map { it.copy(observedValue="edited evidence") })).forEach {
            assertNotEquals(revision.authorityClientKey,PriceTraceCandidatePayload.revision(it).authorityClientKey)
        }
        assertEquals("logical-product",p.clientKey)
        assertEquals(before,YeonsikOcrEnvelopeCodec.encode(envelope(p)))
        assertFalse(PriceTraceCandidatePayload.wire(p).toString().contains("brand_id"))
    }
    @Test fun anonymousSearchParsesAliasAndNeverSendsBlankBearer()=runTest {
        val requests=mutableListOf<PriceObservationHttpRequest>()
        val body="""[{"brand_id":"$child","canonical_name":"Canonical B","parent_brand_id":"$root","parent_canonical_name":"Canonical A","matched_name":"Observed B","match_kind":"alias","logo_url":null,"official_site_url":null}]"""
        val gateway=PriceTraceCanonicalGateway(Store(false),PriceObservationHttpTransport { requests+=it;PriceObservationHttpResponse(200,body) })
        assertEquals(child,gateway.searchBrands("Observed B").single().brandId)
        assertFalse(requests.single().headers.containsKey("Authorization"))
        assertTrue(requests.single().headers.containsKey("apikey"))
        assertThrows(IllegalArgumentException::class.java) { PriceTraceBrandClient.parseSearch(body.replace(child,"invalid-id")) }
        assertThrows(IllegalArgumentException::class.java) { PriceTraceBrandClient.parseSearch(body.replace("\"alias\"","\"fuzzy\"")) }
    }
    @Test fun noSelectionKeepsSingleIngestAndValidatesEchoThenTranslatesToLogicalIdentity()=runTest {
        val p=candidate(); val requests=mutableListOf<PriceObservationHttpRequest>()
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { r ->
            requests+=r; val wire=Json.parseToJsonElement(r.body!!).jsonObject["p_candidate"]!!.jsonObject
            PriceObservationHttpResponse(200,buildJsonObject { put("candidateId",root);put("clientKey",wire.getValue("client_key")) }.toString())
        })
        val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(p)) as ProjectionSubmission.Success
        assertEquals(1,requests.size)
        val sent=Json.parseToJsonElement(requests.single().body!!).jsonObject["p_candidate"]!!.jsonObject
        assertEquals(PriceTraceCandidatePayload.revision(p).authorityClientKey,sent["client_key"]!!.jsonPrimitive.content)
        assertEquals(p.brand,sent["brand"]!!.jsonPrimitive.content)
        assertEquals(PriceTraceCandidatePayload.revision(p).authorityClientKey,PriceTraceCandidatePayload.acceptedKeys(result.metadataJson)[p.clientKey])
        assertTrue(result.metadataJson!!.contains("\"clientKey\":\"${p.clientKey}\""))
    }
    @Test fun successfulLegacyMetadataKeepsAcceptedAuthorityKey()=runTest {
        val p=candidate(); var wireKey=""
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { r ->
            wireKey=Json.parseToJsonElement(r.body!!).jsonObject["p_candidate"]!!.jsonObject["client_key"]!!.jsonPrimitive.content
            PriceObservationHttpResponse(200,"""{"candidateId":"$root","clientKey":"$wireKey"}""")
        })
        val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(p,previous="""{"responses":[{"clientKey":"${p.clientKey}","candidateId":"$root"}]}"""))
        assertTrue(result is ProjectionSubmission.Success); assertEquals(p.clientKey,wireKey)
    }
    @Test fun mismatchedAuthorityEchoFailsClosed()=runTest {
        listOf("\"wrong\"", "null", "123").forEach { echo ->
            val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { PriceObservationHttpResponse(200,"""{"candidateId":"$root","clientKey":$echo}""") })
            assertTrue(PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(candidate())) is ProjectionSubmission.Failure)
        }
    }
    @Test fun canonicalRevisionDropsStaleDraftAndLegacyAuthorityMetadataDurably()=runTest {
        val p = candidate()
        val source = envelope(p)
        val store = InMemoryIngestionSessionStore()
        val orchestrator = IngestionOrchestrator(store, submitters = emptyMap())
        orchestrator.start("revision", "document", source)
        val started = requireNotNull(store.get("revision"))
        val legacy = """{"responses":[{"clientKey":"${p.clientKey}","candidateId":"$root"}]}"""
        store.save(started.copy(projections = started.projections.map {
            if (it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE)
                it.copy(status = ProjectionStatus.UPLOADED, idempotencyKey = "accepted-key", metadataJson = legacy)
            else it
        }))
        val draft = ProductBrandResolutionDraft(p.clientKey, ProductBrandFactField.BRAND, p.brand!!,
            ProductBrandSelectionIntent.SELECTED, root, "Canonical A")
        val selected = orchestrator.setProductBrandResolution("revision", source, draft)
        assertEquals(started.canonicalFingerprint, selected.canonicalFingerprint)
        assertEquals(legacy, selected.projections.single { it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }.metadataJson)
        val edited = source.copy(productCandidates = listOf(p.copy(brand = "edited brand")))
        val revised = (orchestrator.reviseCanonicalDraft("revision", edited) as IngestionStartResult.Success).session
        assertTrue(revised.brandResolutionDrafts.isEmpty())
        val projection = revised.projections.single { it.projection == IngestionProjection.PRICETRACE_PRODUCT_CANDIDATE }
        assertNull(projection.metadataJson)
        assertNull(projection.idempotencyKey)
        assertEquals(p.clientKey, edited.productCandidates.single().clientKey)
        val undone = (orchestrator.reviseCanonicalDraft("revision", source) as IngestionStartResult.Success).session
        assertTrue(undone.brandResolutionDrafts.isEmpty())
        assertTrue(PriceTraceCandidatePayload.acceptedKeys(projection.metadataJson).isEmpty())
    }
    @Test fun catalogReuseMetadataKeepsLogicalIdentityAndRejectsStaleAcceptedRevision()=runTest {
        val p=candidate()
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { r ->
            val key=Json.parseToJsonElement(r.body!!).jsonObject["p_candidate"]!!.jsonObject["client_key"]!!
            PriceObservationHttpResponse(200,"""{"catalogProductId":"$root","clientKey":$key}""")
        })
        val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(p)) as ProjectionSubmission.Success
        assertEquals(root,PriceTraceProductIdentityJson.tryDecode(result.metadataJson).getValue(p.clientKey).catalogProductId)
        assertEquals(PriceTraceCandidatePayload.revision(p).authorityClientKey,PriceTraceCandidatePayload.acceptedKeys(result.metadataJson,listOf(p))[p.clientKey])
        assertTrue(PriceTraceCandidatePayload.acceptedKeys(result.metadataJson,listOf(p.copy(specification="edited"))).isEmpty())
    }
    @Test fun clearIsExplicitAndHierarchyFailureRequiresCorrection()=runTest {
        val p=candidate(); val calls=mutableListOf<PriceObservationHttpRequest>()
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { r ->
            calls+=r
            if(r.url.endsWith("submit_product_candidate_v1")) {
                val key=Json.parseToJsonElement(r.body!!).jsonObject["p_candidate"]!!.jsonObject["client_key"]!!
                PriceObservationHttpResponse(200,"""{"candidateId":"$root","clientKey":$key}""")
            } else PriceObservationHttpResponse(400,"""{"code":"23514","message":"brand_sub_brand_hierarchy_conflict"}""")
        })
        val clear=ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.BRAND,p.brand!!,ProductBrandSelectionIntent.CLEAR)
        val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(p,listOf(clear))) as ProjectionSubmission.Failure
        assertFalse(result.retryable);assertTrue(result.requiresReview)
        val body=Json.parseToJsonElement(calls.last().body!!).jsonObject
        assertEquals(JsonNull,body["p_brand_id"]);assertEquals(JsonPrimitive(true),body["p_user_verified"])
    }
    @Test fun partialFailureRetainsCandidateMetadataAndRetryUsesSameRevision()=runTest {
        val p=candidate();val calls=mutableListOf<PriceObservationHttpRequest>();var fail=true;val stored=linkedMapOf<String,JsonObject>()
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { r ->
            calls+=r; val args=Json.parseToJsonElement(r.body!!).jsonObject
            when {
                r.url.endsWith("submit_product_candidate_v1") -> PriceObservationHttpResponse(200,"""{"candidateId":"$root","clientKey":${args["p_candidate"]!!.jsonObject["client_key"]}}""")
                r.url.endsWith("set_product_candidate_brand_resolution_v1") -> {
                    val field=args["p_source_field"]!!.jsonPrimitive.content
                    if(field=="sub_brand" && fail) { fail=false;PriceObservationHttpResponse(503,"temporary") }
                    else {
                        stored[field]=buildJsonObject {
                            put("product_client_key",args.getValue("p_client_key"));put("request_fingerprint","a".repeat(64));put("source_field",field)
                            put("observed_value",args.getValue("p_observed_value"));put("brand_id",args.getValue("p_brand_id"))
                            put("canonical_name",if(field=="brand") "Canonical A" else "Canonical B");put("brand_active",true)
                            put("parent_brand_id",if(field=="sub_brand") JsonPrimitive(root) else JsonNull);put("parent_canonical_name",if(field=="sub_brand") JsonPrimitive("Canonical A") else JsonNull)
                            put("created_at","2026-10-23T00:00:00Z");put("updated_at","2026-10-23T00:00:00Z")
                        }
                        PriceObservationHttpResponse(200,JsonObject(stored.getValue(field).filterKeys { it in setOf("product_client_key","request_fingerprint","source_field","observed_value","brand_id") } + ("cleared" to JsonPrimitive(false))).toString())
                    }
                }
                else -> PriceObservationHttpResponse(200,JsonArray(stored.values.toList()).toString())
            }
        })
        val drafts=listOf(ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.BRAND,p.brand!!,ProductBrandSelectionIntent.SELECTED,root),
            ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.SUB_BRAND,p.subBrand!!,ProductBrandSelectionIntent.SELECTED,child))
        val submitter=PriceTraceProductCandidateProjectionSubmitter(gateway)
        val first=submitter.submit(request(p,drafts)) as ProjectionSubmission.Failure
        assertTrue(first.retryable);assertNotNull(first.metadataJson)
        val retry=submitter.submit(request(p,drafts,first.metadataJson)) as ProjectionSubmission.Success
        val ingests=calls.filter { it.url.endsWith("submit_product_candidate_v1") }
        assertEquals(ingests[0].body,ingests[1].body)
        assertTrue(calls.first().url.endsWith("submit_product_candidate_v1"))
        assertEquals(2,Json.parseToJsonElement(retry.metadataJson!!).jsonObject["brandResolutions"]!!.jsonArray.size)
    }
    @Test fun sidecarRoundTripAndRawEditInvalidationAreIndependentOfCanonicalJson() {
        val p=candidate(); val d=ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.BRAND,p.brand!!,ProductBrandSelectionIntent.SELECTED,root,"Canonical A")
        assertEquals(listOf(d),ProductBrandResolutionJson.decode(ProductBrandResolutionJson.encode(listOf(d))))
        assertEquals(emptyList<ProductBrandResolutionDraft>(),currentBrandDrafts(listOf(d),listOf(p.copy(brand="edited"))))
        assertEquals(listOf(d),currentBrandDrafts(listOf(d),listOf(p.copy(subBrand="another sub"))))
        assertEquals(emptyList<ProductBrandResolutionDraft>(),ProductBrandResolutionJson.decode("[]"))
        val session=IngestionSession("i","d","key","fingerprint",IngestionReviewStatus.NEEDS_REVIEW,"now","now",emptyList(),brandResolutionDrafts=listOf(d))
        assertEquals("fingerprint",session.copy(brandResolutionDrafts=emptyList()).canonicalFingerprint)
    }
    @Test fun staleDraftIsRejectedBeforeAnyNetwork()=runTest {
        val p=candidate(); var calls=0
        val gateway=PriceTraceCanonicalGateway(Store(),PriceObservationHttpTransport { calls++;error("should not call") })
        val stale=ProductBrandResolutionDraft(p.clientKey,ProductBrandFactField.BRAND,"old",ProductBrandSelectionIntent.CLEAR)
        val result=PriceTraceProductCandidateProjectionSubmitter(gateway).submit(request(p,listOf(stale))) as ProjectionSubmission.Failure
        assertTrue(result.requiresReview);assertFalse(result.retryable);assertEquals(0,calls)
    }
}
