package ps.e7gz.geography.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ps.e7gz.common.web.DataResponse;
import ps.e7gz.common.web.NotImplementedYet;

/** Customer discovery: the active areas (arch 09 §3.2, FR-VEN-12). Public — no access token (§1.2). */
@RestController
@RequestMapping("/api/v1/customer/areas")
@Tag(name = "customer-discovery")
public class CustomerAreaController {

    @GetMapping
    @Operation(operationId = "customerListAreas", summary = "Active areas (FR-VEN-12)")
    public DataResponse<List<CustomerAreaView>> list() {
        throw NotImplementedYet.stub();
    }

    /** An active area as the customer sees it. Names are stored data, returned as stored (FR-VEN-40). */
    @Schema(name = "CustomerArea")
    public record CustomerAreaView(
            @NotNull @Schema(example = "3f6c1d2e-8a4b-4c5d-9e6f-7a8b9c0d1e2f") UUID id,
            @NotNull @Schema(example = "Nasr City") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "مدينة نصر") String nameAr) {
    }
}
