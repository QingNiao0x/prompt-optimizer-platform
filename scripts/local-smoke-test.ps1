param(
    [string]$ApiBaseUrl = 'http://127.0.0.1:8080',
    [switch]$RunOptimization,
    [switch]$AllowRealModel
)

$ErrorActionPreference = 'Stop'

function Invoke-JsonEndpoint {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('GET', 'POST')]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [object]$Body
    )

    $requestParams = @{
        Method      = $Method
        Uri         = $Uri
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $requestParams.ContentType = 'application/json; charset=utf-8'
        $requestParams.Body = $Body | ConvertTo-Json -Depth 12
    }
    return Invoke-RestMethod @requestParams
}

Write-Host "Checking API: $ApiBaseUrl"

$health = Invoke-JsonEndpoint -Method GET -Uri "$ApiBaseUrl/api/v1/health"
if ($health.data.status -ne 'UP') {
    throw "API health check failed: $($health | ConvertTo-Json -Depth 8 -Compress)"
}
Write-Host "[PASS] /api/v1/health, requestId=$($health.requestId)" -ForegroundColor Green

if ($RunOptimization -and -not $AllowRealModel -and $health.data.providerMode -ne 'mock') {
    throw "Optimization smoke test stopped because providerMode is '$($health.data.providerMode)'. Start the API with Mock Provider or pass -AllowRealModel explicitly."
}

$actuator = Invoke-JsonEndpoint -Method GET -Uri "$ApiBaseUrl/actuator/health"
if ($actuator.status -ne 'UP') {
    throw "Actuator health check failed: $($actuator | ConvertTo-Json -Depth 8 -Compress)"
}
Write-Host '[PASS] /actuator/health' -ForegroundColor Green

$contextRequest = @{
    'customDescription' = 'Local Spring Boot project for smoke testing.';
    'files' = @(
        @{
            'path' = 'pom.xml';
            'language' = 'xml';
            'content' = '<artifactId>spring-boot-starter-web</artifactId>';
        }
    );
}
$context = Invoke-JsonEndpoint -Method POST -Uri "$ApiBaseUrl/api/v1/context/analyze" -Body $contextRequest
if ($null -eq $context.data.analysisVersion) {
    throw "Context analysis response is invalid: $($context | ConvertTo-Json -Depth 8 -Compress)"
}
Write-Host "[PASS] /api/v1/context/analyze, analysisVersion=$($context.data.analysisVersion)" -ForegroundColor Green

if ($RunOptimization) {
    if ($AllowRealModel) {
        Write-Warning 'Real model request is explicitly allowed. This may consume API quota.'
    } else {
        Write-Host 'Mock Provider optimization request will be sent.'
    }
    $optimizationRequest = @{
        'rawPrompt' = 'Add a login feature to the user module';
        'context' = $contextRequest;
        'enhancement' = @{
            'templateCode' = 'FEATURE_DEVELOPMENT';
            'includeConversationHistory' = $false;
            'includePermissionBoundaries' = $true;
            'includeExamples' = $false;
        };
        'conversationHistory' = @();
        'permissionPolicy' = @{
            'protectedPaths' = @();
            'requireConfirmationFor' = @();
        };
    }
    $optimization = Invoke-JsonEndpoint -Method POST -Uri "$ApiBaseUrl/api/v1/optimizations" -Body $optimizationRequest
    if ([string]::IsNullOrWhiteSpace($optimization.data.optimizedPrompt)) {
        throw "Optimization response is empty: $($optimization | ConvertTo-Json -Depth 8 -Compress)"
    }
    if (-not $AllowRealModel -and $optimization.data.provider.mock -ne $true) {
        throw "Optimization response was not produced by Mock Provider: $($optimization | ConvertTo-Json -Depth 8 -Compress)"
    }
    Write-Host "[PASS] /api/v1/optimizations, provider=$($optimization.data.provider.provider), model=$($optimization.data.provider.model)" -ForegroundColor Green
}

Write-Host 'Local API smoke test completed.' -ForegroundColor Green
