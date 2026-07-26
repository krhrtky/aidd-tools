export type OrderState = "Draft" | "Confirmed";

/** @aidd.requirement urn:aidd:order:requirement:confirmed-cannot-return-draft */
export function confirm(state: OrderState): OrderState {
  if (state !== "Draft") {
    throw new Error("Only draft orders can be confirmed");
  }
  return "Confirmed";
}

