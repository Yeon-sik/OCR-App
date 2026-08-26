package com.pricetrace.receiptscanner.domain

/**
 * The OCR merchant string is evidence, not a confirmed real-world place.
 * Keep place resolution explicit so a later provider lookup cannot silently
 * turn a plausible name into a public restaurant identity.
 */
enum class PlaceResolutionStatus(val wireValue: String) {
    UNRESOLVED("unresolved"),
    CANDIDATES_READY("candidates_ready"),
    USER_CONFIRMED("user_confirmed"),
    MANUAL_REQUIRED("manual_required"),
}

enum class PlaceCandidateSource(val wireValue: String) {
    OCR("ocr"),
    NAVER_LOCAL("naver_local"),
    VERIFIED_DIRECTORY("verified_directory"),
    MANUAL("manual"),
}

enum class PlaceMatchField {
    NAME,
    BRANCH,
    ADDRESS,
    PHONE,
    BUSINESS_REGISTRATION_NUMBER,
}

data class RestaurantPlaceCandidate(
    val id: String,
    val source: PlaceCandidateSource,
    val displayName: String,
    val restaurantId: String? = null,
    val restaurantLocationId: String? = null,
    val sourceNamespace: String? = null,
    val branchName: String? = null,
    val address: String? = null,
    val roadAddress: String? = null,
    val phone: String? = null,
    val sourceLocationCode: String? = null,
    val detailUrl: String? = null,
    val matchedFields: Set<PlaceMatchField> = emptySet(),
    val evidenceLineReferences: List<String> = emptyList(),
    val confidence: Float? = null,
) {
    init {
        require(id.isNotBlank()) { "Place candidate id must not be blank" }
        require(displayName.isNotBlank()) { "Place candidate display name must not be blank" }
        require(restaurantId == null || restaurantId.isNotBlank()) { "Restaurant id must not be blank" }
        require(restaurantLocationId == null || restaurantLocationId.isNotBlank()) {
            "Restaurant location id must not be blank"
        }
        require(sourceNamespace == null || sourceNamespace.isNotBlank()) {
            "Place candidate source namespace must not be blank"
        }
        require(confidence == null || confidence in 0f..1f) {
            "Place candidate confidence must be between 0 and 1"
        }
    }
}

data class RestaurantPlaceResolution(
    val status: PlaceResolutionStatus = PlaceResolutionStatus.UNRESOLVED,
    val candidates: List<RestaurantPlaceCandidate> = emptyList(),
    val selectedCandidateId: String? = null,
) {
    init {
        require(candidates.map(RestaurantPlaceCandidate::id).distinct().size == candidates.size) {
            "Place candidate ids must be unique"
        }
        require(selectedCandidateId == null || candidates.any { it.id == selectedCandidateId }) {
            "Selected place candidate must exist in candidates"
        }
        when (status) {
            PlaceResolutionStatus.UNRESOLVED,
            PlaceResolutionStatus.MANUAL_REQUIRED,
            -> require(selectedCandidateId == null) {
                "Unresolved or manual place resolution cannot have a selected candidate"
            }

            PlaceResolutionStatus.CANDIDATES_READY -> require(candidates.isNotEmpty()) {
                "Candidates-ready place resolution must contain candidates"
            }

            PlaceResolutionStatus.USER_CONFIRMED -> require(selectedCandidateId != null) {
                "User-confirmed place resolution must have a selected candidate"
            }
        }
    }

    val selectedCandidate: RestaurantPlaceCandidate?
        get() = selectedCandidateId?.let { id -> candidates.firstOrNull { it.id == id } }

    fun confirm(candidateId: String): RestaurantPlaceResolution {
        require(candidates.any { it.id == candidateId }) {
            "Cannot confirm an unknown place candidate: $candidateId"
        }
        return copy(
            status = PlaceResolutionStatus.USER_CONFIRMED,
            selectedCandidateId = candidateId,
        )
    }

    companion object {
        fun unresolved(): RestaurantPlaceResolution = RestaurantPlaceResolution()

        /**
         * Creates an OCR-only candidate. It intentionally never marks the
         * place as confirmed; provider lookup and user selection are separate.
         */
        fun fromParsedReceipt(receipt: ParsedReceipt): RestaurantPlaceResolution {
            val displayName = receipt.merchantName.value?.trim().orEmpty()
            if (displayName.isBlank()) return unresolved()

            val matchedFields = linkedSetOf<PlaceMatchField>().apply {
                add(PlaceMatchField.NAME)
                if (!receipt.branchName.value.isNullOrBlank()) add(PlaceMatchField.BRANCH)
                if (!receipt.address.value.isNullOrBlank()) add(PlaceMatchField.ADDRESS)
                if (!receipt.phone.value.isNullOrBlank()) add(PlaceMatchField.PHONE)
                if (!receipt.businessRegistrationNumber.value.isNullOrBlank()) {
                    add(PlaceMatchField.BUSINESS_REGISTRATION_NUMBER)
                }
            }
            val evidence = listOf(
                receipt.merchantName,
                receipt.branchName,
                receipt.address,
                receipt.phone,
                receipt.businessRegistrationNumber,
            ).flatMap { field -> field.provenance.map { it.ocrLineId } }.distinct()
            val confidence = receipt.merchantName.provenance
                .mapNotNull { it.confidence }
                .maxOrNull()

            return RestaurantPlaceResolution(
                status = PlaceResolutionStatus.CANDIDATES_READY,
                candidates = listOf(
                    RestaurantPlaceCandidate(
                        id = "ocr:${receipt.documentId}",
                        source = PlaceCandidateSource.OCR,
                        displayName = displayName,
                        branchName = receipt.branchName.value?.trim()?.takeIf(String::isNotBlank),
                        address = receipt.address.value?.trim()?.takeIf(String::isNotBlank),
                        phone = receipt.phone.value?.trim()?.takeIf(String::isNotBlank),
                        matchedFields = matchedFields,
                        evidenceLineReferences = evidence,
                        confidence = confidence,
                    ),
                ),
            )
        }
    }
}
