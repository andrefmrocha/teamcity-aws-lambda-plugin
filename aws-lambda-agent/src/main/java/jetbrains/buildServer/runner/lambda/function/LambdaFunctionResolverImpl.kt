package jetbrains.buildServer.runner.lambda.function

import com.amazonaws.services.lambda.AWSLambda
import com.amazonaws.services.lambda.model.*
import com.amazonaws.waiters.WaiterParameters
import jetbrains.buildServer.agent.BuildRunnerContext
import jetbrains.buildServer.runner.lambda.LambdaConstants

class LambdaFunctionResolverImpl(
    private val context: BuildRunnerContext,
    private val awsLambda: AWSLambda,
    private val functionDownloader: FunctionDownloader
) : LambdaFunctionResolver {
    private val lambdaMemory = context.runnerParameters.getValue(LambdaConstants.MEMORY_SIZE_PARAM).toInt()
    private val iamRole = context.runnerParameters.getValue(LambdaConstants.IAM_ROLE_PARAM)
    private val defaultImage = LambdaConstants.DEFAULT_LAMBDA_RUNTIME

    override fun resolveFunction(): String {
        val functionImage = context.runnerParameters[LambdaConstants.ECR_IMAGE_URI_PARAM] ?: defaultImage
        val normalizedFunctionImageName = functionImage.substringAfter("/").substringBefore(":")
        val lambdaFunctionName = "${LambdaConstants.FUNCTION_NAME}-$normalizedFunctionImageName"

        val getFunctionRequest = GetFunctionRequest().apply {
            functionName = lambdaFunctionName
        }

        try {
            //TODO: Check Function Details: TW-75372
            val function = awsLambda.getFunction(getFunctionRequest)
            function.configuration.lastUpdateStatus
            if (doesFunctionConfigurationNeedUpdate(function)) {
                updateFunctionConfiguration(lambdaFunctionName)
            }

            if (functionImage != defaultImage && doesFunctionContainerNeedUpdate(function, functionImage)) {
                updateFunctionContainer(functionImage, lambdaFunctionName)
            }
        } catch (e: ResourceNotFoundException) {
            createFunction(functionImage, lambdaFunctionName)
        } catch (e: AWSLambdaException) {
            // Should the function be executed locally, a 404 will happen
            if (e.statusCode != 404) {
                throw e
            }
        }

        return lambdaFunctionName
    }

    private fun updateFunctionContainer(functionImage: String, lambdaFunctionName: String) {
        System.err.println("Updating function container")
        val updateFunctionCodeRequest = UpdateFunctionCodeRequest().apply {
            functionName = lambdaFunctionName
            imageUri = functionImage
            publish = true
        }

        awsLambda.updateFunctionCode(updateFunctionCodeRequest)
        awaitFunctionStatus(lambdaFunctionName)
    }

    private fun doesFunctionContainerNeedUpdate(function: GetFunctionResult, functionImage: String): Boolean =
        function.code.imageUri != functionImage || functionImage.endsWith(LATEST_TAG)
    // Whenever the container image is the latest, we are unable to know if there have been updates to the image

    private fun updateFunctionConfiguration(
        lambdaFunctionName: String
    ) {
        System.err.println("Updating function config")
        val updateFunctionConfigurationRequest = UpdateFunctionConfigurationRequest().apply {
            functionName = lambdaFunctionName
            role = iamRole
            memorySize = lambdaMemory
        }

        awsLambda.updateFunctionConfiguration(updateFunctionConfigurationRequest)
        awaitFunctionStatus(lambdaFunctionName)
    }

    private fun doesFunctionConfigurationNeedUpdate(
        function: GetFunctionResult
    ): Boolean {
        if (function.configuration.memorySize != lambdaMemory) {
            System.err.println("Difference in $lambdaMemory and ${function.configuration.memorySize}")
            return true
        }
        if (function.configuration.role != iamRole) {
            System.err.println("Difference in $iamRole and ${function.configuration.role}")
            return true
        }
        return false
    }


    private fun createFunction(functionImageUri: String, lambdaFunctionName: String) {
        // TODO: Add logic for function versioning: TW-75371
        val createFunctionRequest = if (functionImageUri == defaultImage) {
            CreateFunctionRequest().apply {
                functionName = lambdaFunctionName
                code = FunctionCode().apply {
                    zipFile = functionDownloader.downloadFunctionCode()
                    handler = LambdaConstants.FUNCTION_HANDLER
                }
                role = iamRole
                publish = true
                packageType = "Zip"
                runtime = LambdaConstants.DEFAULT_LAMBDA_RUNTIME
                memorySize = this@LambdaFunctionResolverImpl.lambdaMemory
                timeout = LambdaConstants.LAMBDA_FUNCTION_MAX_TIMEOUT
            }
        } else {
            CreateFunctionRequest().apply {
                functionName = lambdaFunctionName
                code = FunctionCode().apply {
                    imageUri = functionImageUri
                }
                role = iamRole
                publish = true
                packageType = "Image"
                memorySize = this@LambdaFunctionResolverImpl.lambdaMemory
                timeout = LambdaConstants.LAMBDA_FUNCTION_MAX_TIMEOUT
            }
        }

        awsLambda.createFunction(createFunctionRequest)
        awaitFunctionStatus(lambdaFunctionName)
    }

    private fun awaitFunctionStatus(lambdaFunctionName: String) {
        val waiters = awsLambda.waiters()
        waiters.functionActiveV2().run(
            WaiterParameters(
                GetFunctionRequest().apply {
                    functionName = lambdaFunctionName
                })
        )

        waiters.functionUpdatedV2().run(
            WaiterParameters(
                GetFunctionRequest().apply {
                    functionName = lambdaFunctionName
                })
        )
    }

    companion object {
        private const val LATEST_TAG = ":latest"
    }
}