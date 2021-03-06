package uk.nhs.nhsx.highriskpostcodesupload;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import uk.nhs.nhsx.core.HttpResponses;
import uk.nhs.nhsx.core.Jackson;
import uk.nhs.nhsx.core.aws.cloudfront.AwsCloudFront;

import static uk.nhs.nhsx.core.Jackson.deserializeMaybe;

public class RiskyPostCodesUploadService {

    private final RiskyPostCodesPersistence persistence;
    private final AwsCloudFront awsCloudFront;
    private final String cloudFrontDistributionId;
    private final String cloudFrontInvalidationPattern;

    public RiskyPostCodesUploadService(RiskyPostCodesPersistence persistence,
                                       AwsCloudFront awsCloudFront,
                                       String cloudFrontDistributionId,
                                       String cloudFrontInvalidationPattern) {
        this.persistence = persistence;
        this.awsCloudFront = awsCloudFront;
        this.cloudFrontDistributionId = cloudFrontDistributionId;
        this.cloudFrontInvalidationPattern = cloudFrontInvalidationPattern;
    }

    public APIGatewayProxyResponseEvent upload(String rawJson) {
        return deserializeMaybe(rawJson, RiskyPostDistrictsRequest.class)
            .map(request -> {
                var riskLevels = persistence.retrievePostDistrictRiskLevels();

                var mapper = new RiskyPostCodesMapper(riskLevels);
                var result = mapper.mapOrThrow(request);

                var analyticsCsv = mapper.convertToAnalyticsCsv(request);

                persistence.uploadToBackup(rawJson);
                persistence.uploadToRaw(analyticsCsv);
                persistence.uploadPostDistrictsVersion1(Jackson.toJson(result.riskyPostCodesV1));
                persistence.uploadPostDistrictsVersion2(Jackson.toJson(result.riskyPostCodesV2));

                awsCloudFront.invalidateCache(cloudFrontDistributionId, cloudFrontInvalidationPattern);
                return HttpResponses.accepted("successfully uploaded");
            })
            .orElse(HttpResponses.unprocessableEntity("validation error: unable to deserialize payload"));
    }

}
