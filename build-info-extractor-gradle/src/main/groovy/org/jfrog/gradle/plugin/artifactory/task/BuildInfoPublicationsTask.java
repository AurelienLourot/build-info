/*
 * Copyright (C) 2011 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.gradle.plugin.artifactory.task;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.commons.lang.StringUtils;
import org.apache.ivy.core.IvyPatternHelper;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.publish.Publication;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.ivy.IvyArtifact;
import org.gradle.api.publish.ivy.IvyArtifactSet;
import org.gradle.api.publish.ivy.IvyPublication;
import org.gradle.api.publish.ivy.internal.publication.IvyModuleDescriptorSpecInternal;
import org.gradle.api.publish.ivy.internal.publication.IvyPublicationInternal;
import org.gradle.api.publish.ivy.internal.publisher.IvyNormalizedPublication;
import org.gradle.api.publish.ivy.internal.publisher.IvyPublicationIdentity;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.MavenArtifactSet;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal;
import org.gradle.api.publish.maven.internal.publisher.MavenNormalizedPublication;
import org.gradle.api.publish.maven.internal.publisher.MavenProjectIdentity;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.extractor.clientConfiguration.ArtifactoryClientConfiguration;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.build.extractor.clientConfiguration.LayoutPatterns;
import org.jfrog.gradle.plugin.artifactory.extractor.GradleDeployDetails;
import org.jfrog.gradle.plugin.artifactory.extractor.PublishArtifactInfo;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import javax.xml.namespace.QName;

/**
 * @author Fred Simon
 */
public class BuildInfoPublicationsTask extends BuildInfoBaseTask {

    private static final Logger log = Logging.getLogger(BuildInfoPublicationsTask.class);

    @Input
    @Optional
    private Set<IvyPublication> ivyPublications = Sets.newHashSet();

    @Input
    @Optional
    private Set<MavenPublication> mavenPublications = Sets.newHashSet();

    private boolean publishPublicationsSpecified;

    public void publications(Object... publications) {
        if (publications == null) {
            return;
        }
        for (Object publication : publications) {
            if (publication instanceof CharSequence) {
                Publication publicationObj = getProject().getExtensions()
                        .getByType(PublishingExtension.class).getPublications().findByName(publication.toString());
                if (publicationObj != null) {
                    addPublication(publicationObj);
                } else {
                    log.error("Publication named '{}' does not exist for project '{}' in task '{}'.",
                            publication, getProject().getPath(), getPath());
                }
            } else if (publication instanceof Publication) {
                addPublication((Publication) publication);
            } else {
                log.error("Publication type '{}' not supported in task '{}'.",
                        new Object[]{publication.getClass().getName(), getPath()});
            }
        }
        publishPublicationsSpecified = true;
    }

    private void addPublication(Publication publicationObj) {
        if (publicationObj instanceof IvyPublication) {
            ivyPublications.add((IvyPublication) publicationObj);
        } else if (publicationObj instanceof MavenPublication) {
            mavenPublications.add((MavenPublication) publicationObj);
        } else {
            log.warn("Publication named '{}' in project '{}' is of unknown type '{}'",
                    publicationObj.getName(), getProject().getPath(), publicationObj.getClass());
        }
    }

    public Set<IvyPublication> getIvyPublications() {
        return ivyPublications;
    }

    public Set<MavenPublication> getMavenPublications() {
        return mavenPublications;
    }

    public boolean hasPublications() {
        return !ivyPublications.isEmpty() || !mavenPublications.isEmpty();
    }

    @Override
    protected void checkDependsOnArtifactsToPublish(Project project) {
        // If no publications in the list
        if (!hasPublications()) {
            // If some were declared => Warning
            if (publishPublicationsSpecified) {
                log.warn("None of the specified publications matched for project '{}' - nothing to publish.",
                        project.getPath());
            } else {
                log.debug("No publications specified for project '{}'", project.getPath());
            }
            return;
        }
        for (IvyPublication ivyPublication : ivyPublications) {
            if (!(ivyPublication instanceof IvyPublicationInternal)) {
                // TODO: Check how the output files can be extracted without using getPublishableFiles
                log.warn("Ivy publication name '{}' is of unsupported type '{}'!",
                        ivyPublication.getName(), ivyPublication.getClass());
                continue;
            }
            dependsOn(((IvyPublicationInternal) ivyPublication).getPublishableFiles());
            String capitalizedPublicationName = ivyPublication.getName().substring(0, 1).toUpperCase() + ivyPublication.getName().substring(1);
            dependsOn(String.format("generateDescriptorFileFor%sPublication", capitalizedPublicationName));
        }
        for (MavenPublication mavenPublication : mavenPublications) {
            if (!(mavenPublication instanceof MavenPublicationInternal)) {
                // TODO: Check how the output files can be extracted without using getPublishableFiles
                log.warn("Maven publication name '{}' is of unsupported type '{}'!",
                        mavenPublication.getName(), mavenPublication.getClass());
                continue;
            }
            dependsOn(((MavenPublicationInternal) mavenPublication).getPublishableFiles());
            String capitalizedPublicationName = mavenPublication.getName().substring(0, 1).toUpperCase() + mavenPublication.getName().substring(1);
            dependsOn(String.format("generatePomFileFor%sPublication", capitalizedPublicationName));
        }
    }

    @Override
    protected void collectDescriptorsAndArtifactsForUpload() throws IOException {
        Set<GradleDeployDetails> deployDetailsFromProject = getArtifactDeployDetails();
        deployDetails.addAll(deployDetailsFromProject);
    }

    @Override
    public boolean hasModules() {
        return hasPublications();
    }

    protected Set<GradleDeployDetails> getArtifactDeployDetails() {
        Set<GradleDeployDetails> deployDetails = Sets.newLinkedHashSet();
        if (!hasPublications()) {
            log.info("No publications to publish for project '{}'.", getProject().getPath());
            return deployDetails;
        }

        ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();


        Set<String> processedFiles = Sets.newHashSet();
        for (IvyPublication ivyPublication : ivyPublications) {
            String publicationName = ivyPublication.getName();
            if (!(ivyPublication instanceof IvyPublicationInternal)) {
                // TODO: Check how the descriptor file can be extracted without using asNormalisedPublication
                log.warn("Ivy publication name '{}' is of unsupported type '{}'!",
                        publicationName, ivyPublication.getClass());
                continue;
            }
            IvyPublicationInternal ivyPublicationInternal = (IvyPublicationInternal) ivyPublication;
            IvyNormalizedPublication ivyNormalizedPublication = ivyPublicationInternal.asNormalisedPublication();
            IvyPublicationIdentity projectIdentity = ivyNormalizedPublication.getProjectIdentity();
            Map<QName, String> extraInfo = ivyPublication.getDescriptor().getExtraInfo().asMap();

            // First adding the Ivy descriptor (if the build is configured to add it):
            if (isPublishIvy()) {
                File file = ivyNormalizedPublication.getDescriptorFile();
                DeployDetails.Builder builder = createBuilder(processedFiles, file, publicationName);
                if (builder != null) {
                    PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                            projectIdentity.getModule(), "xml", "ivy", null, extraInfo, file);
                    addIvyArtifactToDeployDetails(deployDetails, publicationName, projectIdentity, builder, artifactInfo);
                }
            }

            IvyArtifactSet artifacts = ivyPublication.getArtifacts();
            for (IvyArtifact artifact : artifacts) {
                File file = artifact.getFile();
                DeployDetails.Builder builder = createBuilder(processedFiles, file, publicationName);
                if (builder == null) continue;
                PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                        artifact.getName(), artifact.getExtension(), artifact.getType(), artifact.getClassifier(),
                        extraInfo, file);
                addIvyArtifactToDeployDetails(deployDetails, publicationName, projectIdentity, builder, artifactInfo);
            }
        }

        for (MavenPublication mavenPublication : mavenPublications) {
            String publicationName = mavenPublication.getName();
            if (!(mavenPublication instanceof MavenPublicationInternal)) {
                // TODO: Check how the descriptor file can be extracted without using asNormalisedPublication
                log.warn("Maven publication name '{}' is of unsupported type '{}'!",
                        publicationName, mavenPublication.getClass());
                continue;
            }
            MavenPublicationInternal mavenPublicationInternal = (MavenPublicationInternal) mavenPublication;
            MavenNormalizedPublication mavenNormalizedPublication = mavenPublicationInternal.asNormalisedPublication();
            MavenProjectIdentity projectIdentity = mavenNormalizedPublication.getProjectIdentity();

            // First adding the Maven descriptor (if the build is configured to add it):
            if (isPublishMaven()) {
                File file = mavenNormalizedPublication.getPomFile();
                DeployDetails.Builder builder = createBuilder(processedFiles, file, publicationName);
                if (builder != null) {
                    PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                            projectIdentity.getArtifactId(), "pom", "pom", null, null, file);
                    addMavenArtifactToDeployDetails(deployDetails, publicationName, projectIdentity, builder, artifactInfo);
                }
            }

            MavenArtifactSet artifacts = mavenPublication.getArtifacts();
            for (MavenArtifact artifact : artifacts) {
                File file = artifact.getFile();
                DeployDetails.Builder builder = createBuilder(processedFiles, file, publicationName);
                if (builder == null) continue;
                PublishArtifactInfo artifactInfo = new PublishArtifactInfo(
                        projectIdentity.getArtifactId(), artifact.getExtension(),
                        artifact.getExtension(), artifact.getClassifier(), null,
                        file);
                addMavenArtifactToDeployDetails(deployDetails, publicationName, projectIdentity, builder, artifactInfo);
            }
        }
        return deployDetails;
    }

    private DeployDetails.Builder createBuilder(Set<String> processedFiles, File file, String publicationName) {
        if (processedFiles.contains(file.getAbsolutePath())) {
            return null;
        }
        if (!file.exists()) {
            throw new GradleException("File '" + file.getAbsolutePath() + "'" +
                    " does not exist, and need to be published from publication " + publicationName);
        }
        processedFiles.add(file.getAbsolutePath());

        DeployDetails.Builder artifactBuilder = new DeployDetails.Builder().file(file);
        try {
            Map<String, String> checksums =
                    FileChecksumCalculator.calculateChecksums(file, "MD5", "SHA1");
            artifactBuilder.md5(checksums.get("MD5")).sha1(checksums.get("SHA1"));
        } catch (Exception e) {
            throw new GradleException(
                    "Failed to calculate checksums for artifact: " + file.getAbsolutePath(), e);
        }
        return artifactBuilder;
    }

    private Map<String, String> getExtraTokens(PublishArtifactInfo artifactInfo) {
        Map<String, String> extraTokens = Maps.newHashMap();
        if (StringUtils.isNotBlank(artifactInfo.getClassifier())) {
            extraTokens.put("classifier", artifactInfo.getClassifier());
        }
        for (Map.Entry<QName, String> extraToken : artifactInfo.getExtraInfo().entrySet()) {
            String key = extraToken.getKey().getLocalPart();
            if (extraTokens.containsKey(key)) {
                throw new GradleException("Duplicated extra info '" + key + "'.");
            }
            extraTokens.put(key, extraToken.getValue());
        }
        return extraTokens;
    }

    private void addIvyArtifactToDeployDetails(Set<GradleDeployDetails> deployDetails, String publicationName,
                                               IvyPublicationIdentity projectIdentity, DeployDetails.Builder builder,
                                               PublishArtifactInfo artifactInfo) {
        ArtifactoryClientConfiguration clientConf = getArtifactoryClientConfiguration();
        ArtifactoryClientConfiguration.PublisherHandler publisherConf = clientConf.publisher;
        String pattern;
        if ("ivy".equals(artifactInfo.getType())) {
            pattern = publisherConf.getIvyPattern();
        } else {
            pattern = publisherConf.getIvyArtifactPattern();
        }
        String gid = projectIdentity.getOrganisation();
        if (publisherConf.isM2Compatible()) {
            gid = gid.replace(".", "/");
        }

        // TODO: Gradle should support multi params
        Map<String, String> extraTokens = getExtraTokens(artifactInfo);
        builder.artifactPath(IvyPatternHelper.substitute(
                pattern, gid, projectIdentity.getModule(),
                projectIdentity.getRevision(), artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publicationName,
                extraTokens, null));
        addArtifactInfoToDeployDetails(deployDetails, publicationName, builder, artifactInfo);
    }

    private void addMavenArtifactToDeployDetails(Set<GradleDeployDetails> deployDetails, String publicationName,
                                                 MavenProjectIdentity projectIdentity, DeployDetails.Builder builder,
                                                 PublishArtifactInfo artifactInfo) {
        Map<String, String> extraTokens = getExtraTokens(artifactInfo);
        builder.artifactPath(IvyPatternHelper.substitute(
                LayoutPatterns.M2_PATTERN, projectIdentity.getGroupId().replace(".", "/"),
                projectIdentity.getArtifactId(),
                projectIdentity.getVersion(),
                artifactInfo.getName(), artifactInfo.getType(),
                artifactInfo.getExtension(), publicationName,
                extraTokens, null));
        addArtifactInfoToDeployDetails(deployDetails, publicationName, builder, artifactInfo);
    }

    private void addArtifactInfoToDeployDetails(Set<GradleDeployDetails> deployDetails, String publicationName,
                                                DeployDetails.Builder builder, PublishArtifactInfo artifactInfo) {
        builder.targetRepository(getArtifactoryClientConfiguration().publisher.getRepoKey());
        Map<String, String> propsToAdd = getPropsToAdd(artifactInfo, publicationName);
        builder.addProperties(propsToAdd);
        DeployDetails details = builder.build();
        deployDetails.add(new GradleDeployDetails(artifactInfo, details, getProject()));
    }
}
