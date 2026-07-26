package example

enum class OrderState {
    DRAFT,
    CONFIRMED,
}

/**
 * @aidd.requirement urn:aidd:order:requirement:confirmed-cannot-return-draft
 */
fun confirm(state: OrderState): OrderState {
    require(state == OrderState.DRAFT)
    return OrderState.CONFIRMED
}

