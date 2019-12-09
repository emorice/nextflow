/*
 * Copyright 2019, Google Inc
 * Copyright 2018, WuxiNextcode
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nextflow.cloud.google.lifesciences

import java.nio.file.Path

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.lifesciences.v2beta.CloudLifeSciences
import com.google.api.services.lifesciences.v2beta.model.Accelerator
import com.google.api.services.lifesciences.v2beta.model.Action
import com.google.api.services.lifesciences.v2beta.model.CancelOperationRequest
import com.google.api.services.lifesciences.v2beta.model.Disk
import com.google.api.services.lifesciences.v2beta.model.Mount
import com.google.api.services.lifesciences.v2beta.model.Operation
import com.google.api.services.lifesciences.v2beta.model.Pipeline
import com.google.api.services.lifesciences.v2beta.model.Resources
import com.google.api.services.lifesciences.v2beta.model.RunPipelineRequest
import com.google.api.services.lifesciences.v2beta.model.ServiceAccount
import com.google.api.services.lifesciences.v2beta.model.VirtualMachine
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.contrib.nio.CloudStorageFileSystem
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.executor.res.AcceleratorResource
/**
 * Helper class for Google Pipelines.
 *
 * @author Ólafur Haukur Flygenring <olafurh@wuxinextcode.com>
 * @author  Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */
@Slf4j
@CompileStatic
class GooglePipelinesHelper {

    private static final String SCOPE_CLOUD_PLATFORM = "https://www.googleapis.com/auth/cloud-platform"
    private static final List<String> ENV_VAR_TO_INCLUDE = ["NXF_DEBUG"]

    CloudLifeSciences client
    GoogleCredentials credentials
    final String applicationName

    /**
     * As defined by Google pipeline API
     * https://cloud.google.com/genomics/reference/rest/Shared.Types/Flag
     */
    static enum ActionFlags {
        FLAG_UNSPECIFIED,
        IGNORE_EXIT_STATUS,
        RUN_IN_BACKGROUND,
        ALWAYS_RUN,
        ENABLE_FUSE,
        PUBLISH_EXPOSED_PORTS,
        DISABLE_IMAGE_PREFETCH,
        DISABLE_STANDARD_ERROR_CAPTURE
    }

    GooglePipelinesHelper(GoogleCredentials credential = null, String name = "Nextflow GoogleLifeScience") {
        this.credentials = credential
        this.applicationName = name
    }

    static String sanitizeName(String name) {
        name.replaceAll(/[^a-zA-Z0-9\-_]+/, '-').take(63)
    }

    GooglePipelinesHelper init() {
        if (!credentials)
            credentials = GoogleCredentials.getApplicationDefault()

        if (credentials.createScopedRequired()) {
            credentials = credentials.createScoped([SCOPE_CLOUD_PLATFORM])
        }

        HttpRequestInitializer requestInitializer = new HttpCredentialsAdapter(credentials);
        client = new CloudLifeSciences.Builder(GoogleNetHttpTransport.newTrustedTransport(), JacksonFactory.defaultInstance, requestInitializer)
                .setApplicationName(applicationName)
                .build()

        return this
    }

    Map<String, String> getEnvironment() {
        System.getenv().findAll { it ->
            ENV_VAR_TO_INCLUDE.contains(it.key)
        }
    }

    Action createAction(String name, String imageUri, List<String> commands, List<Mount> mounts, List<ActionFlags> flags = [], String entrypoint = null) {
        final action = new Action()
                .setContainerName(name)
                .setImageUri(imageUri)
                .setCommands(commands)
                .setMounts(mounts)
                .setEntrypoint(entrypoint)
                .setEnvironment(getEnvironment())

        setFlags(action,flags)
        return action
    }

    protected Action setFlags(Action action, List<ActionFlags> flags) {
        if( ActionFlags.IGNORE_EXIT_STATUS in flags )
            action.setIgnoreExitStatus(true)
        if( ActionFlags.RUN_IN_BACKGROUND in flags )
            action.setRunInBackground(true)
        if( ActionFlags.ALWAYS_RUN in flags)
            action.setAlwaysRun(true)
        if( ActionFlags.ENABLE_FUSE )
            action.setEnableFuse(true)
        if( ActionFlags.PUBLISH_EXPOSED_PORTS in flags )
            action.setPublishExposedPorts(true)
        if( ActionFlags.DISABLE_IMAGE_PREFETCH in flags )
            action.setDisableImagePrefetch(true)
        if( ActionFlags.DISABLE_STANDARD_ERROR_CAPTURE )
            action.setDisableStandardErrorCapture(true)

        return action
    }

    Pipeline createPipeline(List<Action> actions, Resources resources) {
        new Pipeline().setActions(actions).setResources(resources)
    }

    Operation submitPipeline(GooglePipelinesSubmitRequest req) {

        final actions = new ArrayList(5)
        actions.add(createStagingAction(req))
        actions.add(createMainAction(req))
        actions.add(createUnstagingAction(req))

        final pipeline = createPipeline( actions, createResources(req) )

        runPipeline(req.project, req.location, pipeline, ["taskName" : req.taskName])
    }

    protected Resources createResources(GooglePipelinesSubmitRequest req) {
        configureResources(
                req.machineType,
                req.zone,
                req.region,
                req.diskName,
                req.diskSizeGb,
                [SCOPE_CLOUD_PLATFORM],
                req.preemptible,
                req.accelerator
        )
    }

    protected Action createMainAction(GooglePipelinesSubmitRequest req) {
        createAction(
                "$req.taskName-main",
                req.containerImage,
                ['bash', '-c', req.mainScript],
                [req.sharedMount] )
    }

    protected Action createStagingAction(GooglePipelinesSubmitRequest req) {
        createAction(
                "$req.taskName-stage",
                req.fileCopyImage,
                ["bash", "-c", req.stagingScript],
                [req.sharedMount] )
    }

    protected Action createUnstagingAction(GooglePipelinesSubmitRequest req) {
        createAction(
                "$req.taskName-unstage",
                req.fileCopyImage,
                ["bash", "-c", req.unstagingScript],
                [req.sharedMount],
                [ActionFlags.ALWAYS_RUN, ActionFlags.IGNORE_EXIT_STATUS])
    }


    Resources configureResources(String machineType, List<String> zone, List<String> region, String diskName, Integer diskSizeGb, List<String> scopes, boolean preemptible, AcceleratorResource accelerator) {

        def disk = new Disk()
        disk.setName(diskName)
        disk.setSizeGb(diskSizeGb)

        def serviceAccount = new ServiceAccount()
        if (scopes)
            serviceAccount.setScopes(scopes)

        def vm = new VirtualMachine()
                .setMachineType(machineType)
                .setDisks([disk])
                .setServiceAccount(serviceAccount)
                .setPreemptible(preemptible)

        if( accelerator ) {
            final acc = new Accelerator().setType(accelerator.type).setCount(accelerator.request)
            final list = new ArrayList(1)
            list.add(acc)
            vm.setAccelerators(list)
        }

        new Resources()
                .setZones(zone)
                .setRegions(region)
                .setVirtualMachine(vm)
    }

    Operation checkOperationStatus(Operation operation) {
        try {
            client.projects().locations().operations().get(operation.getName()).execute()
        }
        catch( IOException e ) {
            log.warn("Invalid server response fetching operation status: ${operation.getName()}", e)
            return null
        }
    }

    void cancelOperation(Operation operation) {
        try {
            client.projects().locations().operations().cancel(operation.getName(), new CancelOperationRequest()).execute()
        }
        catch( IOException e ) {
            log.debug("Invalid server response cancelling operation: ${operation.getName()} | ${e.message}")
        }
    }

    Operation runPipeline(String project, String location, Pipeline pipeline, Map<String,String> labels = [:]) {
        final parent = "projects/$project/locations/$location"
        try {
            client
                    .projects()
                    .locations()
                    .pipelines()
                    .run(parent,
                            new RunPipelineRequest().setPipeline(pipeline) .setLabels(labels) )
                    .execute()
        }
        catch( IOException e ) {
            log.warn("Invalid server response running pipeline: $pipeline", e)
            return null
        }
    }

    static Path gsPath(String str) {
        assert str.startsWith('gs://')
        str = str.substring(5)
        int p = str.indexOf('/')
        return ( p==-1
                ? CloudStorageFileSystem.forBucket(str).getPath('')
                : CloudStorageFileSystem.forBucket(str.substring(0,p)).getPath(str.substring(p)) )
    }
}
