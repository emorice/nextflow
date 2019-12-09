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

import groovy.transform.CompileStatic
import groovy.transform.ToString
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.exception.AbortOperationException
/**
 * Helper class wrapping configuration required for Google Pipelines.
 *
 * @author Ólafur Haukur Flygenring <olafurh@wuxinextcode.com>
 */
@Slf4j
@ToString(includePackage = false, includeNames = true)
@CompileStatic
class GoogleLifeSciencesConfig {

    String project
    List<String> zones
    List<String> regions
    boolean preemptible
    Path remoteBinDir
    String location
    boolean disableBinDir

    @Deprecated
    GoogleLifeSciencesConfig(String project, List<String> zone, List<String> region, Path remoteBinDir = null, boolean preemptible = false) {
        this.project = project
        this.zones = zone
        this.regions = region
        this.remoteBinDir = remoteBinDir
        this.preemptible = preemptible
        // infer location
        this.location = region ? region.get(0) : null
        if( !location )
            throw new IllegalArgumentException("Missing Google cloud location")
    }

    GoogleLifeSciencesConfig() {}

    static GoogleLifeSciencesConfig fromSession(Session session) {
        try {
            fromSession0(session.config)
        }
        catch (Exception e) {
            session.abort()
            throw e
        }
    }

    protected static GoogleLifeSciencesConfig fromSession0(Map config) {
        def project = config.navigate("google.project") as String
        if( !project )
            throw new AbortOperationException("Missing Google project Id -- Please specify `google.project` setting in your Nextflow config file")

        //check if we have one of the mutual exclusive zone or region specified
        if(!config.navigate("google.zone") && !config.navigate("google.region")){
            throw new AbortOperationException("Missing configuration value 'google.zone' or 'google.region'")
        }

        //check if we have one of the mutual exclusive zone or region specified
        if(config.navigate("google.zone") && config.navigate("google.region")){
            throw new AbortOperationException("You can't specify both 'google.zone' and 'google.region' configuration parameters -- Please remove one of them from your configuration")
        }

        def path = config.navigate('env.PATH')
        if( path ) {
            log.warn "Environment PATH defined in config file is ignored by Google Pipeline executor"
        }

        /*
         * upload local binaries
         */
        boolean disableBinDir = config.navigate('google.lifeSciences.disableRemoteBinDir',false)
        def preemptible = config.navigate("google.lifeSciences.preemptible", true) as boolean


        def zones = (config.navigate("google.zone") as String)?.split(",")?.toList() ?: Collections.<String>emptyList()
        def regions = (config.navigate("google.region") as String)?.split(",")?.toList() ?: Collections.<String>emptyList()
        def location = config.navigate("google.location") as String ?: fallbackToRegionOrZone(regions,zones)

        new GoogleLifeSciencesConfig(
                project: project,
                regions: regions,
                zones: zones,
                location: location,
                preemptible: preemptible,
                disableBinDir: disableBinDir )
    }


    static String fallbackToRegionOrZone(List<String> regions, List<String> zones) {
        if( regions ) {
            if( regions.size()>1 ) {
                log.warn "Google LifeSciences location is missing -- Defaulting to region: ${regions[0]}"
            }
            return regions[0]
        }
        if( zones ) {
            def norm = zones
                    .collect { int p = zones[0].lastIndexOf('-'); p!=-1 ? it.substring(0,p) : it }
                    .unique()
            if( norm.size()>1 ) {
                log.warn "Google LifeSciences location is missing -- Defaulting to zone: ${norm[0]}"
            }

            return norm[0]
        }
        throw new AbortOperationException("Missing Google region or location information")
    }

}