import { SubstituteValue, SubstituteValueType } from "../processor/prosessor.model"

export function getTypedValue(subValue: SubstituteValue): any {
  if (subValue.type == SubstituteValueType.NUMBER) {
    return Number(subValue.value)
  } else if (subValue.type == SubstituteValueType.TEXTUAL) {
    return String(subValue.value)
  } else {
    return subValue.value
  }
}