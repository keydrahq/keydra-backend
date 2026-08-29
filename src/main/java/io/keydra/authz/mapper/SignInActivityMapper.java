package io.keydra.authz.mapper;

import io.keydra.authz.dto.SignInActivity;
import io.keydra.authz.entity.SignInAttempt;
import java.util.Arrays;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

/** Attempt rows to what is shown of them. */
@Mapper(componentModel = "jakarta", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface SignInActivityMapper {

    @Mapping(target = "outcome", expression = "java(attempt.outcome.name())")
    @Mapping(target = "anomalies", source = "anomalies", qualifiedByName = "split")
    SignInActivity toActivity(SignInAttempt attempt);

    List<SignInActivity> toActivity(List<SignInAttempt> attempts);

    /**
     * The stored comma-separated list, as a list.
     *
     * <p>Named and qualified rather than left to MapStruct, which would otherwise apply this
     * conversion to every string it maps — the mistake a mapper in this codebase has made before.
     */
    @Named("split")
    static List<String> split(String anomalies) {
        if (anomalies == null || anomalies.isBlank()) {
            return List.of();
        }
        return Arrays.stream(anomalies.split(","))
                .map(String::trim)
                .filter(one -> !one.isEmpty())
                .toList();
    }
}
