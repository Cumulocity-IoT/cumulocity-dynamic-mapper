package dynamic.mapping.core.cache;

import jakarta.validation.constraints.NotNull;
import java.util.Objects;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode
public class CacheKey {
	@NotNull
	public String externalIdType;
	@NotNull
	public String externalId;

	// @Override
	// public boolean equals(Object o) {
	// if (this == o)
	// return true;
	// if (o == null || getClass() != o.getClass())
	// return false;
	// CacheKey cacheKey = (CacheKey) o;
	// return Objects.equals(externalIdType, cacheKey.externalIdType) &&
	// Objects.equals(externalId, cacheKey.externalId);
	// }

	// @Override
	// public int hashCode() {
	// return Objects.hash(externalIdType, externalId);
	// }
}
