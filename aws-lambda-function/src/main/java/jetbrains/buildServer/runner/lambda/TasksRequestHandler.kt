package jetbrains.buildServer.runner.lambda

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.transfer.TransferManagerBuilder
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.ktor.client.engine.cio.*
import jetbrains.buildServer.runner.lambda.build.LambdaCommandLine
import jetbrains.buildServer.runner.lambda.directory.S3WorkingDirectoryTransfer
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import kotlin.io.path.createTempDirectory

class TasksRequestHandler : RequestStreamHandler {
    private val objectMapper = jacksonObjectMapper()

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val runDetails: RunDetails = objectMapper.readValue(input)

        val detachedBuildApi = MyDetachedBuildApi(runDetails, context, CIO.create())
        val workingDirectoryTransfer = S3WorkingDirectoryTransfer(getTransferManager())

        val workingDirectory = workingDirectoryTransfer.retrieve(runDetails.directoryId, createTempDirectory().toFile())

        val jobs = LambdaCommandLine(runDetails, context.logger, workingDirectory).executeCommandLine(detachedBuildApi)

        runBlocking {
            jobs.awaitAll()
            detachedBuildApi.finishBuild()
        }
    }

    private fun getTransferManager(): TransferManager =
        TransferManagerBuilder.standard()
            .build()
}