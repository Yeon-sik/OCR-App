package com.pricetrace.receiptscanner.ingestion

import com.pricetrace.receiptscanner.review.CanonicalReviewController
import com.pricetrace.receiptscanner.review.CanonicalFieldRegistry
import com.pricetrace.receiptscanner.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RestaurantPurchaseComponentTest {
    @Test fun twoOccupiedMenuLinesSwapAtomicallyAndAuditBothArtifacts() {
        val value = base()
        val main = value.nutrition.single() as IngestionNutrition.RestaurantEstimate
        val record = value.purchaseRecords.single()
        val secondLine = record.lineItems.single().copy(lineKey = "line-2",description = "돈까스")
        val two = value.copy(purchaseRecords = listOf(record.copy(lineItems = record.lineItems + secondLine)),
            nutrition = value.nutrition + main.copy(clientKey = "food-2",menuName = "돈까스"),
            purchaseNutritionLinks = value.purchaseNutritionLinks + value.purchaseNutritionLinks.single().copy(nutritionClientKey = "food-2",purchaseLineKey = "line-2"))
        val controller = CanonicalReviewController(two)
        val choice = CanonicalFieldRegistry.purchaseLineChoice(two,"food-1",two.purchaseRecords.single(),secondLine)
        assertTrue(choice.contains("맞교환"))
        assertTrue(controller.updateField("links[food-1].parent_purchase_line",choice))
        val links = controller.state.value.envelope.purchaseNutritionLinks.associateBy { it.nutritionClientKey }
        assertEquals("line-2",links.getValue("food-1").purchaseLineKey)
        assertEquals("line-1",links.getValue("food-2").purchaseLineKey)
        assertTrue(controller.state.value.edits.any { it.fieldPath == "links[food-2].purchase_line_key" })
        assertTrue(controller.undo())
        assertEquals(two.purchaseNutritionLinks,controller.state.value.envelope.purchaseNutritionLinks)
    }
    @Test fun aLegacyComplimentaryArtifactCanChangeItsRoleWithoutChangingNutrientsOrV2Rules() {
        val value = component()
        val soup = value.nutrition.last() as IngestionNutrition.MealComponentEstimate
        val controller = CanonicalReviewController(value)
        for (role in listOf("side","sauce","beverage","other","complimentary_side")) {
            assertTrue(controller.updateField("links[food-soup].component_role",role))
            assertEquals(soup.estimate,(controller.state.value.envelope.nutrition.last() as IngestionNutrition.RestaurantEstimate).estimate)
            assertEquals(PurchaseNutritionRelation.MENU_COMPONENT,controller.state.value.envelope.purchaseNutritionLinks.last().relationType)
        }
        assertTrue(controller.state.value.edits.any { it.fieldPath == "nutrition[food-soup].kind" })
        assertEquals(1,controller.state.value.envelope.purchaseRecords.single().lineItems.size)
    }
    @Test fun sameNamedChoicesDisplaySourcePairsAndMalformedPairMetadataFailsClosed() {
        val command = PurchaseIdentityCommand("purchase-1","line-1",PurchaseIdentityEntity.RESTAURANT,PurchaseIdentityAction.SEARCH,"학식당")
        val response = Json.parseToJsonElement("""{"entity":"restaurant","query":"학식당","status":"needs_selection","selected":null,"choices":[{"id":"00000000-0000-4000-8000-000000000001","name":"학식당","sourceNamespace":"campus","sourceCode":"north"},{"id":"00000000-0000-4000-8000-000000000002","name":"학식당","sourceNamespace":"campus","sourceCode":"south"}]}""").jsonObject
        val choices = PurchaseIdentityReview.states(PurchaseIdentityReview.merge(null,command,response)).single().choices
        assertNotEquals(choices[0].label,choices[1].label)
        assertTrue(choices[1].label.contains("campus / south"))
        val malformed = JsonObject(response + ("choices" to JsonArray(listOf(JsonObject(response.getValue("choices").jsonArray.first().jsonObject - "sourceCode")))))
        assertThrows(IllegalArgumentException::class.java) { PurchaseIdentityReview.merge(null,command,malformed) }
    }
    @Test fun producerComponentExampleMatchesTheFinalContract() {
        val value = YeonsikOcrEnvelopeCodec.decode(File("../examples/yeonsik-ocr.v5.restaurant-purchase-components.example.json").readText(),"producer-component")
        assertEquals("한성대 학식당",value.purchaseRecords.single().seller)
        assertEquals(6000L,value.purchaseRecords.single().lineItems.single().unitPriceAmountKrw?.toLong())
        assertEquals(2,value.nutrition.size)
        assertEquals(1,value.purchaseNutritionLinks.count { it.componentRole == PurchaseComponentRole.MAIN })
        assertEquals(PurchaseNutritionRelation.MENU_COMPONENT,value.purchaseNutritionLinks.last().relationType)
        val evidence = value.source.sourceFiles.map { LocalEvidence(it.id,it.type,true) }
        assertTrue(IngestionEvidenceGate.evaluate(value,evidence).isAllowed)
    }
    private fun base() = YeonsikOcrEnvelopeCodec.decode(File("../examples/yeonsik-ocr.v5.restaurant-purchase.example.json").readText(),"component")
    private fun component(value: YeonsikOcrEnvelope = base()): YeonsikOcrEnvelope {
        val main = value.nutrition.single() as IngestionNutrition.RestaurantEstimate
        return value.copy(nutrition = value.nutrition + IngestionNutrition.MealComponentEstimate("food-soup",menuName = "국물",estimate = main.estimate),
            purchaseNutritionLinks = value.purchaseNutritionLinks + value.purchaseNutritionLinks.single().copy(nutritionClientKey = "food-soup",
                componentRole = PurchaseComponentRole.COMPLIMENTARY_SIDE,relationType = PurchaseNutritionRelation.MENU_COMPONENT))
    }
    @Test fun legacyThreeKeysAndProjectionDependencyStringRemainIdentical() {
        val value = base()
        val encoded = Json.parseToJsonElement(YeonsikOcrV5Json.encodeDraft(value)).jsonObject
        assertEquals(setOf("purchase_record_client_key","purchase_line_key","nutrition_client_key"),encoded.getValue("links").jsonArray.single().jsonObject.keys)
        val link = value.purchaseNutritionLinks.single()
        assertEquals(PurchaseComponentRole.MAIN,link.componentRole)
        assertEquals("PurchaseNutritionLink(purchaseRecordClientKey=${link.purchaseRecordClientKey}, purchaseLineKey=${link.purchaseLineKey}, nutritionClientKey=${link.nutritionClientKey})",link.toString())
        assertEquals(YeonsikOcrV5Json.canonicalize(value),YeonsikOcrV5Json.canonicalize(YeonsikOcrV5Json.decode(YeonsikOcrV5Json.encodeDraft(value),"other")))
    }
    @Test fun mainAndComplimentarySoupShareRealParentWithoutAZeroPriceOrConsumption() {
        val value = component()
        YeonsikOcrV5Json.validate(value)
        val restored = YeonsikOcrV5Json.decode(YeonsikOcrV5Json.encodeDraft(value),"restored")
        assertEquals(YeonsikOcrV5Json.canonicalize(value),YeonsikOcrV5Json.canonicalize(restored))
        assertEquals(1,restored.purchaseRecords.single().lineItems.size)
        assertEquals(2,restored.nutrition.size)
        assertTrue(restored.consumption.isEmpty())
        assertFalse(CanonicalProjectionPlanner.plan(restored).eligible.contains(IngestionProjection.FITNESS_MEAL))
        assertEquals(2,ReviewViewModel.fromCanonical(restored).rows.count { it.section == "구매 행과 영양 연결" })
        val soup = ReviewViewModel.fromCanonical(restored).rows.single { it.id == "purchase-nutrition:food-soup" }
        assertEquals("국물",soup.item)
        assertTrue(soup.details.any { it.value == "complimentary_side" })
    }
    @Test fun duplicateMainDanglingDuplicateNutritionAndFakePhotoEvidenceReject() {
        val value = component()
        val soup = value.purchaseNutritionLinks.last()
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(nutrition = value.nutrition.map {
            if (it is IngestionNutrition.MealComponentEstimate) IngestionNutrition.RestaurantEstimate(it.clientKey,null,it.menuName,it.estimate) else it },
            purchaseNutritionLinks = value.purchaseNutritionLinks.dropLast(1) + soup.copy(componentRole = PurchaseComponentRole.MAIN,relationType = PurchaseNutritionRelation.PURCHASE_LINE))) }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(purchaseNutritionLinks = value.purchaseNutritionLinks + soup)) }
        assertThrows(IllegalArgumentException::class.java) { YeonsikOcrV5Json.validate(value.copy(purchaseNutritionLinks = value.purchaseNutritionLinks.dropLast(1) + soup.copy(purchaseLineKey = "missing"))) }
        val evidence = value.source.sourceFiles.filter { it.type != SourceAttachmentType.FOOD_PHOTO }.map { LocalEvidence(it.id,it.type,true) }
        assertFalse(IngestionEvidenceGate.evaluate(value,evidence,artifactKeys = setOf(IngestionArtifactKeys.nutrition("food-soup"))).isAllowed)
        assertFalse(IngestionEvidenceGate.evaluate(value,evidence,verificationBasis = VerificationBasis.MANUAL_CANONICAL_REVIEW,explicitUserConfirmation = true).isAllowed)
        assertNull(PurchaseNutritionIdentity.exact(value,"food-soup",null))
        assertNull(PurchaseNutritionIdentity.ownerConfirmed(value,"food-soup",null))
    }
    @Test fun paidSideUsesItsRealLineAndAtomicReassignmentRevalidatesAndSupportsUndo() {
        val value = base()
        val record = value.purchaseRecords.single()
        val extra = record.copy(clientKey = "purchase-2",lineItems = listOf(record.lineItems.single().copy(lineKey = "paid-side",description = "돈까스")))
        val two = value.copy(purchaseRecords = listOf(record,extra))
        val controller = CanonicalReviewController(two)
        val before = controller.state.value.canonicalFingerprint
        assertTrue(controller.updateField("links[food-1].parent_purchase_line",CanonicalFieldRegistry.purchaseLineChoice(extra,extra.lineItems.single())))
        assertNotEquals(before,controller.state.value.canonicalFingerprint)
        assertEquals("purchase-2",controller.state.value.envelope.purchaseNutritionLinks.single().purchaseRecordClientKey)
        assertTrue(controller.state.value.edits.any { it.fieldPath == "links[food-1].purchase_record_client_key" })
        assertTrue(controller.state.value.edits.any { it.fieldPath == "links[food-1].purchase_line_key" })
        assertTrue(controller.updateField("links[food-1].component_role","side"))
        assertEquals(PurchaseNutritionRelation.PURCHASE_LINE,controller.state.value.envelope.purchaseNutritionLinks.single().relationType)
        assertTrue(controller.undo())
        assertTrue(controller.undo())
        assertEquals(before,controller.state.value.canonicalFingerprint)
        assertFalse(controller.updateField("links[food-1].parent_purchase_line","missing"))
    }
    @Test fun runtimeSearchSelectDeferAndRestartPreserveSourceAndCheckpoint() = runBlocking {
        val value = base()
        val record = value.purchaseRecords.single()
        val line = record.lineItems.single()
        val sourceId = "00000000-0000-4000-8000-000000000020"
        val restaurantId = "00000000-0000-4000-8000-000000000021"
        var calls = 0
        val store = InMemoryIngestionSessionStore()
        val submitter = object : IngestionProjectionSubmitter,PurchaseIdentityReviewSubmitter {
            override suspend fun submit(request: ProjectionRequest) = error("search must reuse saved purchase checkpoint")
            override suspend fun reviewPurchaseIdentity(request: PurchaseIdentityReviewRequest): ProjectionSubmission {
                calls++
                return ProjectionSubmission.Success(sourceId,metadataJson = buildJsonObject {
                    put("identityReview",buildJsonObject { put("entity",request.command.entity.wireValue); put("query",request.command.query)
                        put("status",if(request.command.action == PurchaseIdentityAction.SELECT) "owner_confirmed" else "needs_selection")
                        val choice = buildJsonObject { put("id",restaurantId); put("name","한성대학교 학식당") }
                        put("choices",JsonArray(listOf(choice))); put("selected",if(request.command.action == PurchaseIdentityAction.SELECT) choice else JsonNull) })
                }.toString(),requiresReview = true)
            }
        }
        val useCase = CanonicalIngestionUseCase(store,mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to submitter))
        val evidence = value.source.sourceFiles.map { LocalEvidence(it.id,it.type,true) }
        val imported = useCase.importJson(YeonsikOcrV5Json.encodeDraft(value),"runtime","runtime",evidence) as CanonicalImportResult.Success
        val verified = useCase.confirm("runtime",imported.envelope,evidence).envelope
        val session = store.get("runtime")!!
        val metadata = buildJsonObject { put("sources",JsonArray(listOf(buildJsonObject {
            put("purchaseRecordClientKey",record.clientKey); put("purchaseSourceId",sourceId); put("sourceAcceptanceStatus","accepted")
            put("sourceSaved",true); put("lineAuthorityVersion","purchase-line-authority.v1")
            put("lineResults",JsonArray(listOf(buildJsonObject { put("lineKey",line.lineKey); put("kind","restaurant_purchase"); put("observationCreated",false)
                put("sourceAcceptanceStatus","accepted"); put("observationStatus","not_created"); put("authoritativeIds",JsonNull)
                put("authorityStatus","unresolved"); put("merchantResolutionStatus","unresolved"); put("menuResolutionStatus","unresolved") })))
        }))) }.toString()
        store.save(session.copy(projections = session.projections.map { if (it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION)
            it.copy(metadataJson = metadata,remoteId = sourceId,idempotencyKey = "saved-purchase-key") else it }))
        val command = PurchaseIdentityCommand(record.clientKey,line.lineKey!!,PurchaseIdentityEntity.RESTAURANT,PurchaseIdentityAction.SEARCH,"한성")
        assertTrue(useCase.reviewPurchaseIdentity("runtime",verified,command) is ProjectionSubmission.Success)
        assertEquals(1,calls)
        assertTrue(useCase.reviewPurchaseIdentity("runtime",verified,command.copy(action = PurchaseIdentityAction.SELECT,selectedId = "forged")) is ProjectionSubmission.Failure)
        val restarted = CanonicalIngestionUseCase(store,mapOf(IngestionProjection.PRICETRACE_PRICE_OBSERVATION to submitter))
        assertTrue(restarted.reviewPurchaseIdentity("runtime",verified,command.copy(action = PurchaseIdentityAction.SELECT,selectedId = restaurantId)) is ProjectionSubmission.Success)
        assertTrue(restarted.reviewPurchaseIdentity("runtime",verified,command.copy(action = PurchaseIdentityAction.DEFER)) is ProjectionSubmission.Success)
        val after = store.get("runtime")!!
        assertEquals("saved-purchase-key",after.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.idempotencyKey)
        assertEquals(session.canonicalFingerprint,after.canonicalFingerprint)
        assertEquals(YeonsikOcrV5Json.canonicalize(value),YeonsikOcrV5Json.canonicalize(verified))
        assertEquals(2,calls)
        assertEquals("deferred",PurchaseIdentityReview.states(after.projections.single { it.projection == IngestionProjection.PRICETRACE_PRICE_OBSERVATION }.metadataJson).single().status)
    }
}
