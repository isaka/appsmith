package com.appsmith.server.services.ce;

import com.appsmith.external.constants.AnalyticsEvents;
import com.appsmith.external.constants.ErrorReferenceDocUrl;
import com.appsmith.external.dtos.GitBranchDTO;
import com.appsmith.external.dtos.GitLogDTO;
import com.appsmith.external.dtos.GitStatusDTO;
import com.appsmith.external.dtos.MergeStatusDTO;
import com.appsmith.external.git.GitExecutor;
import com.appsmith.external.git.constants.GitConstants;
import com.appsmith.external.git.constants.GitSpan;
import com.appsmith.external.models.Datasource;
import com.appsmith.external.models.DatasourceStorage;
import com.appsmith.git.service.GitExecutorImpl;
import com.appsmith.server.acl.AclPermission;
import com.appsmith.server.actioncollections.base.ActionCollectionService;
import com.appsmith.server.applications.base.ApplicationService;
import com.appsmith.server.configurations.EmailConfig;
import com.appsmith.server.constants.Assets;
import com.appsmith.server.constants.Entity;
import com.appsmith.server.constants.FieldName;
import com.appsmith.server.constants.GitDefaultCommitMessage;
import com.appsmith.server.datasources.base.DatasourceService;
import com.appsmith.server.domains.Application;
import com.appsmith.server.domains.ApplicationMode;
import com.appsmith.server.domains.AutoCommitConfig;
import com.appsmith.server.domains.GitArtifactMetadata;
import com.appsmith.server.domains.GitAuth;
import com.appsmith.server.domains.GitDeployKeys;
import com.appsmith.server.domains.GitProfile;
import com.appsmith.server.domains.Plugin;
import com.appsmith.server.domains.User;
import com.appsmith.server.domains.UserData;
import com.appsmith.server.domains.Workspace;
import com.appsmith.server.dtos.ApplicationImportDTO;
import com.appsmith.server.dtos.ApplicationJson;
import com.appsmith.server.dtos.AutoCommitProgressDTO;
import com.appsmith.server.dtos.GitCommitDTO;
import com.appsmith.server.dtos.GitConnectDTO;
import com.appsmith.server.dtos.GitDocsDTO;
import com.appsmith.server.dtos.GitMergeDTO;
import com.appsmith.server.dtos.GitPullDTO;
import com.appsmith.server.exceptions.AppsmithError;
import com.appsmith.server.exceptions.AppsmithException;
import com.appsmith.server.exports.internal.ExportService;
import com.appsmith.server.helpers.CollectionUtils;
import com.appsmith.server.helpers.GitDeployKeyGenerator;
import com.appsmith.server.helpers.GitFileUtils;
import com.appsmith.server.helpers.GitPrivateRepoHelper;
import com.appsmith.server.helpers.GitUtils;
import com.appsmith.server.helpers.RedisUtils;
import com.appsmith.server.helpers.ResponseUtils;
import com.appsmith.server.helpers.ce.GitAutoCommitHelper;
import com.appsmith.server.imports.internal.ImportService;
import com.appsmith.server.migrations.JsonSchemaVersions;
import com.appsmith.server.newactions.base.NewActionService;
import com.appsmith.server.newpages.base.NewPageService;
import com.appsmith.server.plugins.base.PluginService;
import com.appsmith.server.repositories.GitDeployKeysRepository;
import com.appsmith.server.services.AnalyticsService;
import com.appsmith.server.services.ApplicationPageService;
import com.appsmith.server.services.SessionUserService;
import com.appsmith.server.services.UserDataService;
import com.appsmith.server.services.UserService;
import com.appsmith.server.services.WorkspaceService;
import com.appsmith.server.solutions.ApplicationPermission;
import com.appsmith.server.solutions.DatasourcePermission;
import com.appsmith.server.solutions.WorkspacePermission;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.errors.CannotDeleteCurrentBranchException;
import org.eclipse.jgit.api.errors.CheckoutConflictException;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.util.StringUtils;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.Exceptions;
import reactor.core.observability.micrometer.Micrometer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.appsmith.external.constants.AnalyticsEvents.GIT_ADD_PROTECTED_BRANCH;
import static com.appsmith.external.constants.AnalyticsEvents.GIT_REMOVE_PROTECTED_BRANCH;
import static com.appsmith.external.git.constants.GitConstants.CONFLICTED_SUCCESS_MESSAGE;
import static com.appsmith.external.git.constants.GitConstants.DEFAULT_COMMIT_MESSAGE;
import static com.appsmith.external.git.constants.GitConstants.EMPTY_COMMIT_ERROR_MESSAGE;
import static com.appsmith.external.git.constants.GitConstants.GIT_CONFIG_ERROR;
import static com.appsmith.external.git.constants.GitConstants.GIT_PROFILE_ERROR;
import static com.appsmith.external.git.constants.GitConstants.MERGE_CONFLICT_BRANCH_NAME;
import static com.appsmith.external.git.constants.GitSpan.OPS_COMMIT;
import static com.appsmith.external.git.constants.GitSpan.OPS_STATUS;
import static com.appsmith.git.constants.AppsmithBotAsset.APPSMITH_BOT_USERNAME;
import static com.appsmith.server.constants.ArtifactType.APPLICATION;
import static com.appsmith.server.constants.FieldName.DEFAULT;
import static com.appsmith.server.constants.SerialiseArtifactObjective.VERSION_CONTROL;
import static com.appsmith.server.helpers.DefaultResourcesUtils.createDefaultIdsOrUpdateWithGivenResourceIds;
import static com.appsmith.server.helpers.GitUtils.MAX_RETRIES;
import static com.appsmith.server.helpers.GitUtils.RETRY_DELAY;
import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;
import static org.apache.commons.lang.ObjectUtils.defaultIfNull;

/**
 * Git APIs are slow today because these needs to communicate with remote repo and/or serialise and de-serialise the
 * application. This process takes time and the client may cancel the request. This leads to the flow getting stopped
 * midway producing corrupted states.
 * We use the synchronous sink to ensure that even though the client may have cancelled the flow, git operations should
 * proceed uninterrupted and whenever the user refreshes the page, we will have the sane state. synchronous sink does
 * not take subscription cancellations into account. This means that even if the subscriber has cancelled its
 * subscription, the create method still generates its event.
 */
@Slf4j
@RequiredArgsConstructor
@Import({GitExecutorImpl.class})
@Service
public class GitServiceCEImpl implements GitServiceCE {

    private final UserService userService;
    private final UserDataService userDataService;
    private final SessionUserService sessionUserService;
    private final ApplicationService applicationService;
    private final ApplicationPageService applicationPageService;
    private final NewPageService newPageService;
    private final NewActionService newActionService;
    private final ActionCollectionService actionCollectionService;
    private final GitFileUtils fileUtils;
    private final ImportService importService;
    private final ExportService exportService;
    private final GitExecutor gitExecutor;
    private final ResponseUtils responseUtils;
    private final EmailConfig emailConfig;
    private final AnalyticsService analyticsService;
    private final GitDeployKeysRepository gitDeployKeysRepository;
    private final DatasourceService datasourceService;
    private final PluginService pluginService;
    private final DatasourcePermission datasourcePermission;
    private final ApplicationPermission applicationPermission;
    private final WorkspacePermission workspacePermission;
    private final WorkspaceService workspaceService;
    private final RedisUtils redisUtils;
    private final ObservationRegistry observationRegistry;
    private final GitPrivateRepoHelper gitPrivateRepoHelper;
    private final TransactionalOperator transactionalOperator;
    private final GitAutoCommitHelper gitAutoCommitHelper;

    @Override
    public Mono<Application> updateGitMetadata(String applicationId, GitArtifactMetadata gitArtifactMetadata) {

        if (Optional.ofNullable(gitArtifactMetadata).isEmpty()) {
            return Mono.error(
                    new AppsmithException(AppsmithError.INVALID_PARAMETER, "Git metadata values cannot be null"));
        }

        // For default application we expect a GitAuth to be a part of gitMetadata. We are using save method to leverage
        // @Encrypted annotation used for private SSH keys
        // applicationService.save sets the transient fields so no need to set it again from this method
        return applicationService
                .findById(applicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    application.setGitApplicationMetadata(gitArtifactMetadata);
                    return applicationService.save(application);
                });
    }

    @Override
    public Mono<GitArtifactMetadata> getGitApplicationMetadata(String defaultApplicationId) {
        return Mono.zip(
                        getApplicationById(defaultApplicationId, applicationPermission.getEditPermission()),
                        userDataService.getForCurrentUser())
                .map(tuple -> {
                    Application application = tuple.getT1();
                    UserData userData = tuple.getT2();
                    Map<String, GitProfile> gitProfiles = new HashMap<>();
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    if (!CollectionUtils.isNullOrEmpty(userData.getGitProfiles())) {
                        gitProfiles.put(DEFAULT, userData.getGitProfileByKey(DEFAULT));
                        gitProfiles.put(defaultApplicationId, userData.getGitProfileByKey(defaultApplicationId));
                    }
                    if (gitData == null) {
                        GitArtifactMetadata res = new GitArtifactMetadata();
                        res.setGitProfiles(gitProfiles);
                        return res;
                    }
                    gitData.setGitProfiles(gitProfiles);
                    if (gitData.getGitAuth() != null) {
                        gitData.setPublicKey(gitData.getGitAuth().getPublicKey());
                    }
                    gitData.setDocUrl(Assets.GIT_DEPLOY_KEY_DOC_URL);
                    return gitData;
                });
    }

    @Override
    public Mono<Map<String, GitProfile>> updateOrCreateGitProfileForCurrentUser(
            GitProfile gitProfile, String defaultApplicationId) {

        // Throw error in following situations:
        // 1. Updating or creating global git profile (defaultApplicationId = "default") and update is made with empty
        //    authorName or authorEmail
        // 2. Updating or creating repo specific profile and user want to use repo specific profile but provided empty
        //    values for authorName and email

        if ((DEFAULT.equals(defaultApplicationId) || FALSE.equals(gitProfile.getUseGlobalProfile()))
                && StringUtils.isEmptyOrNull(gitProfile.getAuthorName())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Author Name"));
        } else if ((DEFAULT.equals(defaultApplicationId) || FALSE.equals(gitProfile.getUseGlobalProfile()))
                && StringUtils.isEmptyOrNull(gitProfile.getAuthorEmail())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Author Email"));
        } else if (StringUtils.isEmptyOrNull(defaultApplicationId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.APPLICATION_ID));
        }

        if (DEFAULT.equals(defaultApplicationId)) {
            gitProfile.setUseGlobalProfile(null);
        } else if (!TRUE.equals(gitProfile.getUseGlobalProfile())) {
            gitProfile.setUseGlobalProfile(FALSE);
        }

        return sessionUserService
                .getCurrentUser()
                .flatMap(user -> userService.findByEmail(user.getEmail()))
                .flatMap(user -> userDataService
                        .getForUser(user.getId())
                        .flatMap(userData -> {
                            // GitProfiles will be null if the user has not created any git profile.
                            GitProfile savedProfile = userData.getGitProfileByKey(defaultApplicationId);
                            GitProfile defaultGitProfile = userData.getGitProfileByKey(DEFAULT);

                            if (savedProfile == null || !savedProfile.equals(gitProfile) || defaultGitProfile == null) {
                                userData.setGitProfiles(userData.setGitProfileByKey(defaultApplicationId, gitProfile));

                                // Assign appsmith user profile as a fallback git profile
                                if (defaultGitProfile == null) {
                                    GitProfile userProfile = new GitProfile();
                                    String authorName = StringUtils.isEmptyOrNull(user.getName())
                                            ? user.getUsername().split("@")[0]
                                            : user.getName();
                                    userProfile.setAuthorEmail(user.getEmail());
                                    userProfile.setAuthorName(authorName);
                                    userProfile.setUseGlobalProfile(null);
                                    userData.setGitProfiles(userData.setGitProfileByKey(DEFAULT, userProfile));
                                }

                                // Update userData here
                                UserData requiredUpdates = new UserData();
                                requiredUpdates.setGitProfiles(userData.getGitProfiles());
                                return userDataService
                                        .updateForUser(user, requiredUpdates)
                                        .map(UserData::getGitProfiles);
                            }
                            return Mono.just(userData.getGitProfiles());
                        })
                        .switchIfEmpty(Mono.defer(() -> {
                            // If profiles are empty use Appsmith's user profile as git default profile
                            GitProfile profile = new GitProfile();
                            String authorName = StringUtils.isEmptyOrNull(user.getName())
                                    ? user.getUsername().split("@")[0]
                                    : user.getName();

                            profile.setAuthorName(authorName);
                            profile.setAuthorEmail(user.getEmail());

                            UserData requiredUpdates = new UserData();
                            requiredUpdates.setGitProfiles(Map.of(DEFAULT, gitProfile));
                            return userDataService
                                    .updateForUser(user, requiredUpdates)
                                    .map(UserData::getGitProfiles);
                        }))
                        .filter(profiles -> !CollectionUtils.isNullOrEmpty(profiles)));
    }

    @Override
    public Mono<Map<String, GitProfile>> updateOrCreateGitProfileForCurrentUser(GitProfile gitProfile) {
        gitProfile.setUseGlobalProfile(null);
        return updateOrCreateGitProfileForCurrentUser(gitProfile, DEFAULT);
    }

    @Override
    public Mono<GitProfile> getDefaultGitProfileOrCreateIfEmpty() {
        // Get default git profile if the default is empty then use Appsmith profile as a fallback value
        return getGitProfileForUser(DEFAULT).flatMap(gitProfile -> {
            if (StringUtils.isEmptyOrNull(gitProfile.getAuthorName())
                    || StringUtils.isEmptyOrNull(gitProfile.getAuthorEmail())) {
                return updateGitProfileWithAppsmithProfile(DEFAULT);
            }
            gitProfile.setUseGlobalProfile(null);
            return Mono.just(gitProfile);
        });
    }

    @Override
    public Mono<GitProfile> getGitProfileForUser(String defaultApplicationId) {
        return userDataService.getForCurrentUser().map(userData -> {
            GitProfile gitProfile = userData.getGitProfileByKey(defaultApplicationId);
            if (gitProfile != null && gitProfile.getUseGlobalProfile() == null) {
                gitProfile.setUseGlobalProfile(true);
            } else if (gitProfile == null) {
                // If the profile is requested for repo specific using the applicationId
                GitProfile gitProfile1 = new GitProfile();
                gitProfile1.setAuthorName("");
                gitProfile1.setAuthorEmail("");
                gitProfile1.setUseGlobalProfile(true);
                return gitProfile1;
            }
            return gitProfile;
        });
    }

    private Mono<GitProfile> updateGitProfileWithAppsmithProfile(String key) {
        return sessionUserService
                .getCurrentUser()
                .flatMap(user -> userService.findByEmail(user.getEmail()))
                .flatMap(currentUser -> {
                    GitProfile gitProfile = new GitProfile();
                    String authorName = StringUtils.isEmptyOrNull(currentUser.getName())
                            ? currentUser.getUsername().split("@")[0]
                            : currentUser.getName();
                    gitProfile.setAuthorEmail(currentUser.getEmail());
                    gitProfile.setAuthorName(authorName);
                    gitProfile.setUseGlobalProfile(null);
                    return userDataService.getForUser(currentUser).flatMap(userData -> {
                        UserData updates = new UserData();
                        if (CollectionUtils.isNullOrEmpty(userData.getGitProfiles())) {
                            updates.setGitProfiles(Map.of(key, gitProfile));
                        } else {
                            userData.getGitProfiles().put(key, gitProfile);
                            updates.setGitProfiles(userData.getGitProfiles());
                        }
                        return userDataService
                                .updateForUser(currentUser, updates)
                                .thenReturn(gitProfile);
                    });
                });
    }

    /**
     * This method will make a commit to local repo
     * This is used directly from client, and we need to acquire file lock before starting to keep the application in a sane state
     *
     * @param commitDTO            information required for making a commit
     * @param defaultApplicationId application branch on which the commit needs to be done
     * @param doAmend              if we want to amend the commit with the earlier one, used in connect flow
     * @return success message
     */
    @Override
    public Mono<String> commitApplication(
            GitCommitDTO commitDTO, String defaultApplicationId, String branchName, boolean doAmend) {
        return this.commitApplication(commitDTO, defaultApplicationId, branchName, doAmend, true);
    }

    /**
     * This method will make a commit to local repo and is used internally in flows like create, merge branch
     * Since the lock is already acquired by the other flows, we do not need to acquire file lock again
     *
     * @param commitDTO            information required for making a commit
     * @param defaultApplicationId application branch on which the commit needs to be done
     * @return success message
     */
    public Mono<String> commitApplication(GitCommitDTO commitDTO, String defaultApplicationId, String branchName) {
        return this.commitApplication(commitDTO, defaultApplicationId, branchName, false, false);
    }

    /**
     * @param commitDTO            information required for making a commit
     * @param defaultApplicationId application branch on which the commit needs to be done
     * @param branchName           branch name for the commit flow
     * @param doAmend              if we want to amend the commit with the earlier one, used in connect flow
     * @param isFileLock           boolean value indicates whether the file lock is needed to complete the operation
     * @return success message
     */
    private Mono<String> commitApplication(
            GitCommitDTO commitDTO,
            String defaultApplicationId,
            String branchName,
            boolean doAmend,
            boolean isFileLock) {
        /*
        1. Check if application exists and user have sufficient permissions
        2. Check if branch name exists in git metadata
        3. Save application to the existing local repo
        4. Commit application : git add, git commit (Also check if git init required)
         */

        String commitMessage = commitDTO.getCommitMessage();
        StringBuilder result = new StringBuilder();

        if (commitMessage == null || commitMessage.isEmpty()) {
            commitDTO.setCommitMessage(DEFAULT_COMMIT_MESSAGE + GitDefaultCommitMessage.CONNECT_FLOW.getReason());
        }
        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }

        boolean isSystemGeneratedTemp = false;
        if (commitDTO.getCommitMessage().contains(DEFAULT_COMMIT_MESSAGE)) {
            isSystemGeneratedTemp = true;
        }

        boolean isSystemGenerated = isSystemGeneratedTemp;
        Mono<String> commitMono = this.getApplicationById(
                        defaultApplicationId, applicationPermission.getEditPermission())
                .zipWhen(application ->
                        gitPrivateRepoHelper.isBranchProtected(application.getGitApplicationMetadata(), branchName))
                .map(objects -> {
                    if (objects.getT2()) {
                        throw new AppsmithException(
                                AppsmithError.GIT_ACTION_FAILED,
                                "commit",
                                "Cannot commit to protected branch " + branchName);
                    }
                    return objects.getT1();
                })
                .flatMap(application -> {
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    if (TRUE.equals(isFileLock)) {
                        return addFileLock(gitData.getDefaultApplicationId()).then(Mono.just(application));
                    }
                    return Mono.just(application);
                })
                .flatMap(defaultApplication -> {
                    GitArtifactMetadata defaultGitMetadata = defaultApplication.getGitApplicationMetadata();
                    if (Optional.ofNullable(defaultGitMetadata).isEmpty()) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
                    }
                    // Check if the repo is public for current application and if the user have changed the access after
                    // the connection
                    final String workspaceId = defaultApplication.getWorkspaceId();
                    return GitUtils.isRepoPrivate(defaultGitMetadata.getBrowserSupportedRemoteUrl())
                            .flatMap(isPrivate -> {
                                // Check the repo limit if the visibility status is updated, or it is private
                                if (!isPrivate.equals(defaultGitMetadata.getIsRepoPrivate())
                                        || isPrivate.equals(TRUE)) {
                                    defaultGitMetadata.setIsRepoPrivate(isPrivate);
                                    defaultApplication.setGitApplicationMetadata(defaultGitMetadata);
                                    return applicationService
                                            .save(defaultApplication)
                                            // Check if the private repo count is less than the allowed repo count
                                            .flatMap(application ->
                                                    gitPrivateRepoHelper.isRepoLimitReached(workspaceId, false))
                                            .flatMap(isRepoLimitReached -> {
                                                if (FALSE.equals(isRepoLimitReached)) {
                                                    return Mono.just(defaultApplication);
                                                }
                                                throw new AppsmithException(AppsmithError.GIT_APPLICATION_LIMIT_ERROR);
                                            });
                                } else {
                                    return Mono.just(defaultApplication);
                                }
                            });
                })
                .then(applicationService.findByBranchNameAndDefaultApplicationId(
                        branchName, defaultApplicationId, applicationPermission.getEditPermission()))
                .flatMap((branchedApplication) -> {
                    GitArtifactMetadata gitArtifactMetadata = branchedApplication.getGitApplicationMetadata();
                    if (gitArtifactMetadata == null) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
                    }
                    String errorEntity = "";
                    if (StringUtils.isEmptyOrNull(gitArtifactMetadata.getBranchName())) {
                        errorEntity = "branch name";
                    } else if (StringUtils.isEmptyOrNull(gitArtifactMetadata.getDefaultApplicationId())) {
                        errorEntity = "default application";
                    } else if (StringUtils.isEmptyOrNull(gitArtifactMetadata.getRepoName())) {
                        errorEntity = "repository name";
                    }

                    if (!errorEntity.isEmpty()) {
                        return Mono.error(new AppsmithException(
                                AppsmithError.INVALID_GIT_CONFIGURATION, "Unable to find " + errorEntity));
                    }
                    return Mono.zip(
                            exportService.exportByArtifactId(branchedApplication.getId(), VERSION_CONTROL, APPLICATION),
                            Mono.just(branchedApplication));
                })
                .flatMap(tuple -> {
                    ApplicationJson applicationJson = (ApplicationJson) tuple.getT1();
                    Application childApplication = tuple.getT2();
                    GitArtifactMetadata gitData = childApplication.getGitApplicationMetadata();
                    Path baseRepoSuffix = Paths.get(
                            childApplication.getWorkspaceId(),
                            gitData.getDefaultApplicationId(),
                            gitData.getRepoName());
                    Mono<Path> repoPathMono;
                    try {
                        repoPathMono = fileUtils.saveApplicationToLocalRepoWithAnalytics(
                                baseRepoSuffix, applicationJson, gitData.getBranchName());
                    } catch (IOException | GitAPIException e) {
                        return Mono.error(e);
                    }
                    gitData.setLastCommittedAt(Instant.now());
                    Mono<Application> branchedApplicationMono = updateGitMetadata(childApplication.getId(), gitData);
                    return Mono.zip(
                            repoPathMono,
                            userDataService.getGitProfileForCurrentUser(defaultApplicationId),
                            branchedApplicationMono,
                            Mono.just(childApplication));
                })
                .onErrorResume(e -> {
                    log.error("Error in commit flow: ", e);
                    if (e instanceof RepositoryNotFoundException) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.REPOSITORY_NOT_FOUND, defaultApplicationId));
                    } else if (e instanceof AppsmithException) {
                        return Mono.error(e);
                    }
                    return Mono.error(new AppsmithException(AppsmithError.GIT_FILE_SYSTEM_ERROR, e.getMessage()));
                })
                .flatMap(tuple -> {
                    Path baseRepoPath = tuple.getT1();
                    GitProfile authorProfile = tuple.getT2();
                    Application childApplication = tuple.getT3();
                    GitArtifactMetadata gitApplicationData = childApplication.getGitApplicationMetadata();

                    if (authorProfile == null || StringUtils.isEmptyOrNull(authorProfile.getAuthorName())) {
                        String errorMessage = "Unable to find git author configuration for logged-in user. You can set "
                                + "up a git profile from the user profile section.";
                        return addAnalyticsForGitOperation(
                                        AnalyticsEvents.GIT_COMMIT,
                                        childApplication,
                                        AppsmithError.INVALID_GIT_CONFIGURATION.getErrorType(),
                                        AppsmithError.INVALID_GIT_CONFIGURATION.getMessage(errorMessage),
                                        childApplication
                                                .getGitApplicationMetadata()
                                                .getIsRepoPrivate())
                                .flatMap(user -> Mono.error(
                                        new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, errorMessage)));
                    }
                    result.append("Commit Result : ");
                    Mono<String> gitCommitMono = gitExecutor
                            .commitArtifact(
                                    baseRepoPath,
                                    commitMessage,
                                    authorProfile.getAuthorName(),
                                    authorProfile.getAuthorEmail(),
                                    false,
                                    doAmend)
                            .onErrorResume(error -> {
                                if (error instanceof EmptyCommitException) {
                                    return Mono.just(EMPTY_COMMIT_ERROR_MESSAGE);
                                }
                                return addAnalyticsForGitOperation(
                                                AnalyticsEvents.GIT_COMMIT,
                                                childApplication,
                                                error.getClass().getName(),
                                                error.getMessage(),
                                                childApplication
                                                        .getGitApplicationMetadata()
                                                        .getIsRepoPrivate())
                                        .then(Mono.error(new AppsmithException(
                                                AppsmithError.GIT_ACTION_FAILED, "commit", error.getMessage())));
                            });

                    return Mono.zip(gitCommitMono, Mono.just(childApplication));
                })
                .flatMap(tuple -> {
                    Application childApplication = tuple.getT2();
                    String commitStatus = tuple.getT1();
                    result.append(commitStatus);

                    if (TRUE.equals(commitDTO.getDoPush())) {
                        // Push flow
                        result.append(".\nPush Result : ");
                        return pushApplication(childApplication.getId(), false, false)
                                .map(pushResult -> result.append(pushResult).toString())
                                .zipWith(Mono.just(childApplication));
                    }
                    return Mono.zip(Mono.just(result.toString()), Mono.just(childApplication));
                })
                .flatMap(tuple -> {
                    Application childApplication = tuple.getT2();
                    String status = tuple.getT1();
                    return Mono.zip(
                            Mono.just(status),
                            publishAndOrGetApplication(childApplication.getId(), commitDTO.getDoPush()));
                })
                // Add BE analytics
                .flatMap(tuple -> {
                    String status = tuple.getT1();
                    Application childApplication = tuple.getT2();
                    // Update json schema versions so that we can detect if the next update was made by DB migration or
                    // by the user
                    Application update = new Application();
                    // Reset migration related fields before commit to detect the updates correctly between the commits
                    update.setClientSchemaVersion(JsonSchemaVersions.clientVersion);
                    update.setServerSchemaVersion(JsonSchemaVersions.serverVersion);
                    update.setIsManualUpdate(false);

                    return applicationService
                            .update(childApplication.getId(), update)
                            // Release the file lock on git repo
                            .flatMap(application -> {
                                if (TRUE.equals(isFileLock)) {
                                    return releaseFileLock(childApplication
                                            .getGitApplicationMetadata()
                                            .getDefaultApplicationId());
                                }
                                return Mono.just(application);
                            })
                            .then(addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_COMMIT,
                                    childApplication,
                                    "",
                                    "",
                                    childApplication.getGitApplicationMetadata().getIsRepoPrivate(),
                                    isSystemGenerated))
                            .thenReturn(status)
                            .name(OPS_COMMIT)
                            .tap(Micrometer.observation(observationRegistry));
                });

        return Mono.create(sink -> {
            commitMono.subscribe(sink::success, sink::error, null, sink.currentContext());
        });
    }

    /**
     * Method to get commit history for application branch
     *
     * @param defaultApplicationId application for which the commit history is needed
     * @return list of commits
     */
    @Override
    public Mono<List<GitLogDTO>> getCommitHistory(String defaultApplicationId, String branchName) {

        Mono<List<GitLogDTO>> commitHistoryMono = applicationService
                .findByBranchNameAndDefaultApplicationId(
                        branchName, defaultApplicationId, applicationPermission.getReadPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    if (gitData == null
                            || StringUtils.isEmptyOrNull(
                                    application.getGitApplicationMetadata().getBranchName())) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
                    }
                    Path baseRepoSuffix = Paths.get(
                            application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());
                    // Checkout to branch
                    return Mono.zip(
                            gitExecutor
                                    .checkoutToBranch(baseRepoSuffix, gitData.getBranchName())
                                    .onErrorResume(e -> Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED, "checkout", e.getMessage()))),
                            Mono.just(baseRepoSuffix));
                })
                .flatMap(tuple -> {
                    Path baseRepoSuffix = tuple.getT2();
                    return gitExecutor
                            .getCommitHistory(baseRepoSuffix)
                            .onErrorResume(e -> Mono.error(
                                    new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "log", e.getMessage())));
                });

        return Mono.create(
                sink -> commitHistoryMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    /**
     * Connect the application from Appsmith to a git repo
     * This is the prerequisite step needed to perform all the git operation for an application
     * We are implementing the deployKey approach and since the deploy-keys are repo level these keys are store under application.
     * Each application is equal to a repo in the git(and each branch creates a new application with default application as parent)
     *
     * @param gitConnectDTO applicationId - this is used to link the local git repo to an application
     *                      remoteUrl - used for connecting to remote repo etc
     * @return Application object with the updated data
     */
    @Override
    public Mono<Application> connectApplicationToGit(
            String defaultApplicationId, GitConnectDTO gitConnectDTO, String originHeader) {
        /*
         *  Connecting the application for the first time
         *  The ssh keys is already present in application object from generate SSH key step
         *  We would be updating the remote url and default branchName
         * */

        if (StringUtils.isEmptyOrNull(gitConnectDTO.getRemoteUrl())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Remote Url"));
        }

        if (StringUtils.isEmptyOrNull(originHeader)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.ORIGIN));
        }

        Mono<UserData> currentUserMono = userDataService
                .getForCurrentUser()
                .filter(userData -> !CollectionUtils.isNullOrEmpty(userData.getGitProfiles()))
                .switchIfEmpty(
                        Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_PROFILE_ERROR)));

        Mono<Map<String, GitProfile>> profileMono = updateOrCreateGitProfileForCurrentUser(
                        gitConnectDTO.getGitProfile(), defaultApplicationId)
                .switchIfEmpty(
                        Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_PROFILE_ERROR)));

        final String browserSupportedUrl;
        try {
            browserSupportedUrl = GitUtils.convertSshUrlToBrowserSupportedUrl(gitConnectDTO.getRemoteUrl());
        } catch (AppsmithException error) {
            return Mono.error(error);
        }

        Mono<Boolean> isPrivateRepoMono =
                GitUtils.isRepoPrivate(browserSupportedUrl).cache();

        Mono<Application> connectApplicationMono = profileMono
                .then(getApplicationById(defaultApplicationId, applicationPermission.getGitConnectPermission()))
                .zipWith(isPrivateRepoMono)
                .flatMap(tuple -> {
                    Application application = tuple.getT1();
                    boolean isRepoPrivate = tuple.getT2();
                    // Check if the repo is public
                    if (!isRepoPrivate) {
                        return Mono.just(application);
                    }
                    // Check the limit for number of private repo
                    return gitPrivateRepoHelper
                            .isRepoLimitReached(application.getWorkspaceId(), true)
                            .flatMap(isRepoLimitReached -> {
                                if (FALSE.equals(isRepoLimitReached)) {
                                    return Mono.just(application);
                                }
                                return addAnalyticsForGitOperation(
                                                AnalyticsEvents.GIT_PRIVATE_REPO_LIMIT_EXCEEDED,
                                                application,
                                                AppsmithError.GIT_APPLICATION_LIMIT_ERROR.getErrorType(),
                                                AppsmithError.GIT_APPLICATION_LIMIT_ERROR.getMessage(),
                                                true)
                                        .flatMap(ignore -> Mono.error(
                                                new AppsmithException(AppsmithError.GIT_APPLICATION_LIMIT_ERROR)));
                            });
                })
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                        return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                    } else {
                        String repoName = GitUtils.getRepoName(gitConnectDTO.getRemoteUrl());
                        Path repoSuffix = Paths.get(application.getWorkspaceId(), defaultApplicationId, repoName);
                        Mono<String> defaultBranchMono = gitExecutor
                                .cloneRemoteIntoArtifactRepo(
                                        repoSuffix,
                                        gitConnectDTO.getRemoteUrl(),
                                        gitArtifactMetadata.getGitAuth().getPrivateKey(),
                                        gitArtifactMetadata.getGitAuth().getPublicKey())
                                .onErrorResume(error -> {
                                    log.error("Error while cloning the remote repo, ", error);
                                    return fileUtils
                                            .deleteLocalRepo(repoSuffix)
                                            .then(addAnalyticsForGitOperation(
                                                    AnalyticsEvents.GIT_CONNECT,
                                                    application,
                                                    error.getClass().getName(),
                                                    error.getMessage(),
                                                    application
                                                            .getGitApplicationMetadata()
                                                            .getIsRepoPrivate()))
                                            .flatMap(app -> {
                                                if (error instanceof TransportException) {
                                                    return Mono.error(new AppsmithException(
                                                            AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                                                }
                                                if (error instanceof InvalidRemoteException) {
                                                    return Mono.error(new AppsmithException(
                                                            AppsmithError.INVALID_GIT_CONFIGURATION,
                                                            error.getMessage()));
                                                }
                                                if (error instanceof TimeoutException) {
                                                    return Mono.error(
                                                            new AppsmithException(AppsmithError.GIT_EXECUTION_TIMEOUT));
                                                }
                                                if (error instanceof ClassCastException) {
                                                    // To catch TransportHttp cast error in case HTTP URL is passed
                                                    // instead of SSH URL
                                                    if (error.getMessage().contains("TransportHttp")) {
                                                        return Mono.error(new AppsmithException(
                                                                AppsmithError.INVALID_GIT_SSH_URL));
                                                    }
                                                }
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.GIT_GENERIC_ERROR, error.getMessage()));
                                            });
                                });
                        return Mono.zip(
                                Mono.just(application), defaultBranchMono, Mono.just(repoName), Mono.just(repoSuffix));
                    }
                })
                .flatMap(tuple -> {
                    Application application = tuple.getT1();
                    String defaultBranch = tuple.getT2();
                    String repoName = tuple.getT3();
                    Path repoPath = tuple.getT4();
                    final String applicationId = application.getId();
                    final String workspaceId = application.getWorkspaceId();
                    try {
                        return fileUtils
                                .checkIfDirectoryIsEmpty(repoPath)
                                .zipWith(isPrivateRepoMono)
                                .flatMap(objects -> {
                                    boolean isEmpty = objects.getT1();
                                    boolean isRepoPrivate = objects.getT2();
                                    if (!isEmpty) {
                                        return addAnalyticsForGitOperation(
                                                        AnalyticsEvents.GIT_CONNECT,
                                                        application,
                                                        AppsmithError.INVALID_GIT_REPO.getErrorType(),
                                                        AppsmithError.INVALID_GIT_REPO.getMessage(),
                                                        isRepoPrivate)
                                                .then(Mono.error(
                                                        new AppsmithException(AppsmithError.INVALID_GIT_REPO)));
                                    } else {
                                        GitArtifactMetadata gitArtifactMetadata =
                                                application.getGitApplicationMetadata();
                                        gitArtifactMetadata.setDefaultApplicationId(applicationId);
                                        gitArtifactMetadata.setBranchName(defaultBranch);
                                        gitArtifactMetadata.setDefaultBranchName(defaultBranch);
                                        gitArtifactMetadata.setRemoteUrl(gitConnectDTO.getRemoteUrl());
                                        gitArtifactMetadata.setRepoName(repoName);
                                        gitArtifactMetadata.setBrowserSupportedRemoteUrl(browserSupportedUrl);

                                        gitArtifactMetadata.setIsRepoPrivate(isRepoPrivate);
                                        gitArtifactMetadata.setLastCommittedAt(Instant.now());

                                        // Set branchName for each application resource
                                        return exportService
                                                .exportByArtifactId(applicationId, VERSION_CONTROL, APPLICATION)
                                                .flatMap(artifactExchangeJson -> {
                                                    ApplicationJson applicationJson =
                                                            (ApplicationJson) artifactExchangeJson;
                                                    applicationJson
                                                            .getExportedApplication()
                                                            .setGitApplicationMetadata(gitArtifactMetadata);
                                                    return importService
                                                            .importArtifactInWorkspaceFromGit(
                                                                    workspaceId,
                                                                    applicationId,
                                                                    applicationJson,
                                                                    defaultBranch)
                                                            .map(importableArtifact ->
                                                                    (Application) importableArtifact);
                                                });
                                    }
                                })
                                .onErrorResume(e -> {
                                    if (e instanceof IOException) {
                                        return Mono.error(new AppsmithException(
                                                AppsmithError.GIT_FILE_SYSTEM_ERROR, e.getMessage()));
                                    }
                                    return Mono.error(e);
                                });
                    } catch (IOException e) {
                        log.error("Error while cloning the remote repo, {}", e.getMessage());
                        return Mono.error(new AppsmithException(AppsmithError.GIT_FILE_SYSTEM_ERROR, e.getMessage()));
                    }
                })
                .flatMap(application -> {
                    String repoName = GitUtils.getRepoName(gitConnectDTO.getRemoteUrl());
                    String defaultPageId = "";
                    if (!application.getPages().isEmpty()) {
                        defaultPageId = application.getPages().stream()
                                .filter(applicationPage ->
                                        applicationPage.getIsDefault().equals(TRUE))
                                .collect(Collectors.toList())
                                .get(0)
                                .getId();
                    }
                    String viewModeUrl = Paths.get(
                                    "/", Entity.APPLICATIONS, "/", application.getId(), Entity.PAGES, defaultPageId)
                            .toString();
                    String editModeUrl = Paths.get(viewModeUrl, "edit").toString();
                    // Initialize the repo with readme file
                    try {
                        return Mono.zip(
                                        fileUtils
                                                .initializeReadme(
                                                        Paths.get(
                                                                application.getWorkspaceId(),
                                                                defaultApplicationId,
                                                                repoName,
                                                                "README.md"),
                                                        originHeader + viewModeUrl,
                                                        originHeader + editModeUrl)
                                                .onErrorMap(throwable -> {
                                                    log.error("Error while initialising git repo, {0}", throwable);
                                                    return new AppsmithException(
                                                            AppsmithError.GIT_FILE_SYSTEM_ERROR,
                                                            Exceptions.unwrap(throwable)
                                                                    .getMessage());
                                                }),
                                        currentUserMono)
                                .flatMap(tuple -> {
                                    UserData userData = tuple.getT2();
                                    GitProfile profile = userData.getGitProfileByKey(defaultApplicationId);
                                    if (profile == null
                                            || StringUtils.isEmptyOrNull(profile.getAuthorName())
                                            || TRUE.equals(profile.getUseGlobalProfile())) {

                                        profile = userData.getGitProfileByKey(DEFAULT);
                                    }
                                    return gitExecutor.commitArtifact(
                                            tuple.getT1(),
                                            DEFAULT_COMMIT_MESSAGE + GitDefaultCommitMessage.CONNECT_FLOW.getReason(),
                                            profile.getAuthorName(),
                                            profile.getAuthorEmail(),
                                            false,
                                            false);
                                })
                                .flatMap(ignore -> {
                                    // Commit and push application to check if the SSH key has the write access
                                    GitCommitDTO commitDTO = new GitCommitDTO();
                                    commitDTO.setDoPush(true);
                                    commitDTO.setCommitMessage(
                                            DEFAULT_COMMIT_MESSAGE + GitDefaultCommitMessage.CONNECT_FLOW.getReason());

                                    return this.commitApplication(
                                                    commitDTO,
                                                    defaultApplicationId,
                                                    application
                                                            .getGitApplicationMetadata()
                                                            .getBranchName(),
                                                    true)
                                            .onErrorResume(error ->
                                                    // If the push fails remove all the cloned files from local repo
                                                    this.detachRemote(defaultApplicationId)
                                                            .flatMap(isDeleted -> {
                                                                if (error instanceof TransportException) {
                                                                    return addAnalyticsForGitOperation(
                                                                                    AnalyticsEvents.GIT_CONNECT,
                                                                                    application,
                                                                                    error.getClass()
                                                                                            .getName(),
                                                                                    error.getMessage(),
                                                                                    application
                                                                                            .getGitApplicationMetadata()
                                                                                            .getIsRepoPrivate())
                                                                            .then(Mono.error(new AppsmithException(
                                                                                    AppsmithError
                                                                                            .INVALID_GIT_SSH_CONFIGURATION,
                                                                                    error.getMessage())));
                                                                }
                                                                return Mono.error(new AppsmithException(
                                                                        AppsmithError.GIT_ACTION_FAILED,
                                                                        "push",
                                                                        error.getMessage()));
                                                            }));
                                })
                                .then(addAnalyticsForGitOperation(
                                        AnalyticsEvents.GIT_CONNECT,
                                        application,
                                        application.getGitApplicationMetadata().getIsRepoPrivate()))
                                .map(responseUtils::updateApplicationWithDefaultResources);
                    } catch (IOException e) {
                        log.error("Error while cloning the remote repo, {}", e.getMessage());
                        return Mono.error(new AppsmithException(AppsmithError.GIT_FILE_SYSTEM_ERROR, e.getMessage()));
                    }
                });

        return Mono.create(
                sink -> connectApplicationMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<String> pushApplication(String defaultApplicationId, String branchName) {

        if (StringUtils.isEmptyOrNull(branchName)) {
            throw new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME);
        }
        return applicationService
                .findBranchedApplicationId(branchName, defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(applicationId -> pushApplication(applicationId, true, true));
    }

    /**
     * Push flow for dehydrated apps
     *
     * @param applicationId application which needs to be pushed to remote repo
     * @return Success message
     */
    private Mono<String> pushApplication(String applicationId, boolean doPublish, boolean isFileLock) {

        Mono<String> pushStatusMono = publishAndOrGetApplication(applicationId, doPublish)
                .flatMap(application -> {
                    if (applicationId.equals(
                            application.getGitApplicationMetadata().getDefaultApplicationId())) {
                        return Mono.just(application);
                    }
                    return applicationService
                            .findById(application.getGitApplicationMetadata().getDefaultApplicationId())
                            .map(defaultApp -> {
                                application
                                        .getGitApplicationMetadata()
                                        .setGitAuth(defaultApp
                                                .getGitApplicationMetadata()
                                                .getGitAuth());
                                return application;
                            });
                })
                .flatMap(application -> {
                    if (TRUE.equals(isFileLock)) {
                        return addFileLock(
                                        application.getGitApplicationMetadata().getDefaultApplicationId())
                                .map(status -> application);
                    }
                    return Mono.just(application);
                })
                .flatMap(application -> {
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();

                    if (gitData == null
                            || StringUtils.isEmptyOrNull(gitData.getBranchName())
                            || StringUtils.isEmptyOrNull(gitData.getDefaultApplicationId())
                            || StringUtils.isEmptyOrNull(gitData.getGitAuth().getPrivateKey())) {

                        return Mono.error(
                                new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
                    }
                    Path baseRepoSuffix = Paths.get(
                            application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

                    GitAuth gitAuth = gitData.getGitAuth();
                    return gitExecutor
                            .checkoutToBranch(
                                    baseRepoSuffix,
                                    application.getGitApplicationMetadata().getBranchName())
                            .then(gitExecutor
                                    .pushApplication(
                                            baseRepoSuffix,
                                            gitData.getRemoteUrl(),
                                            gitAuth.getPublicKey(),
                                            gitAuth.getPrivateKey(),
                                            gitData.getBranchName())
                                    .zipWith(Mono.just(application)))
                            .onErrorResume(error -> addAnalyticsForGitOperation(
                                            AnalyticsEvents.GIT_PUSH,
                                            application,
                                            error.getClass().getName(),
                                            error.getMessage(),
                                            application
                                                    .getGitApplicationMetadata()
                                                    .getIsRepoPrivate())
                                    .flatMap(application1 -> {
                                        if (error instanceof TransportException) {
                                            return Mono.error(
                                                    new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                                        }
                                        return Mono.error(new AppsmithException(
                                                AppsmithError.GIT_ACTION_FAILED, "push", error.getMessage()));
                                    }));
                })
                .flatMap(tuple -> {
                    String pushResult = tuple.getT1();
                    Application application = tuple.getT2();
                    return pushApplicationErrorRecovery(pushResult, application).zipWith(Mono.just(tuple.getT2()));
                })
                // Add BE analytics
                .flatMap(tuple -> {
                    String pushStatus = tuple.getT1();
                    Application application = tuple.getT2();
                    if (TRUE.equals(isFileLock)) {
                        return releaseFileLock(
                                        application.getGitApplicationMetadata().getDefaultApplicationId())
                                .map(status -> tuple);
                    }
                    return Mono.zip(Mono.just(pushStatus), Mono.just(application));
                })
                .flatMap(tuple -> {
                    String pushStatus = tuple.getT1();
                    Application application = tuple.getT2();
                    return addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_PUSH,
                                    application,
                                    application.getGitApplicationMetadata().getIsRepoPrivate())
                            .thenReturn(pushStatus);
                })
                .name(GitSpan.OPS_PUSH)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> pushStatusMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    /**
     * This method is used to recover from the errors that can occur during the push operation
     * Mostly happens when the remote branch is protected or any specific rules in place on the branch.
     * Since the users will be in a bad state where the changes are committed locally, but they are
     * not able to push them changes or revert the changes either.
     * 1. Push rejected due to branch protection rules on remote, reset hard prev commit
     *
     * @param pushResult  status of git push operation
     * @param application application data to be used for analytics
     * @return status of the git push flow
     */
    private Mono<String> pushApplicationErrorRecovery(String pushResult, Application application) {
        if (pushResult.contains("REJECTED_NONFASTFORWARD")) {

            return addAnalyticsForGitOperation(
                            AnalyticsEvents.GIT_PUSH,
                            application,
                            AppsmithError.GIT_UPSTREAM_CHANGES.getErrorType(),
                            AppsmithError.GIT_UPSTREAM_CHANGES.getMessage(),
                            application.getGitApplicationMetadata().getIsRepoPrivate())
                    .flatMap(application1 -> Mono.error(new AppsmithException(AppsmithError.GIT_UPSTREAM_CHANGES)));
        } else if (pushResult.contains("REJECTED_OTHERREASON") || pushResult.contains("pre-receive hook declined")) {
            Path path = Paths.get(
                    application.getWorkspaceId(),
                    application.getGitApplicationMetadata().getDefaultApplicationId(),
                    application.getGitApplicationMetadata().getRepoName());
            return gitExecutor
                    .resetHard(path, application.getGitApplicationMetadata().getBranchName())
                    .then(Mono.error(new AppsmithException(
                            AppsmithError.GIT_ACTION_FAILED,
                            "push",
                            "Unable to push changes as pre-receive hook declined. Please make sure that you don't have any rules enabled on the branch "
                                    + application.getGitApplicationMetadata().getBranchName())));
        }
        return Mono.just(pushResult);
    }

    /**
     * Method to remove all the git metadata for the application and connected resources. This will remove:
     * - local repo
     * - all the branched applications present in DB except for default application
     *
     * @param defaultApplicationId application which needs to be disconnected from git connection
     * @return Application data
     */
    @Override
    public Mono<Application> detachRemote(String defaultApplicationId) {
        Mono<Application> disconnectMono = getApplicationById(
                        defaultApplicationId, applicationPermission.getGitConnectPermission())
                .flatMap(defaultApplication -> {
                    if (Optional.ofNullable(defaultApplication.getGitApplicationMetadata())
                                    .isEmpty()
                            || isInvalidDefaultApplicationGitMetadata(defaultApplication.getGitApplicationMetadata())) {
                        return Mono.error(new AppsmithException(
                                AppsmithError.INVALID_GIT_CONFIGURATION,
                                "Please reconfigure the application to connect to git repo"));
                    }
                    // Remove the git contents from file system
                    GitArtifactMetadata gitArtifactMetadata = defaultApplication.getGitApplicationMetadata();
                    String repoName = gitArtifactMetadata.getRepoName();
                    Path repoSuffix = Paths.get(
                            defaultApplication.getWorkspaceId(),
                            gitArtifactMetadata.getDefaultApplicationId(),
                            repoName);
                    String defaultApplicationBranchName = gitArtifactMetadata.getBranchName();
                    String remoteUrl = gitArtifactMetadata.getRemoteUrl();
                    String privateKey = gitArtifactMetadata.getGitAuth().getPrivateKey();
                    String publicKey = gitArtifactMetadata.getGitAuth().getPublicKey();
                    return Mono.zip(
                            gitExecutor.listBranches(repoSuffix),
                            Mono.just(defaultApplication),
                            Mono.just(repoSuffix),
                            Mono.just(defaultApplicationBranchName));
                })
                .flatMap(tuple -> {
                    Application defaultApplication = tuple.getT2();
                    Path repoSuffix = tuple.getT3();
                    List<String> branch = tuple.getT1().stream()
                            .map(GitBranchDTO::getBranchName)
                            .filter(branchName -> !branchName.startsWith("origin"))
                            .collect(Collectors.toList());

                    // Remove the parent application branch name from the list
                    branch.remove(tuple.getT4());
                    defaultApplication.setGitApplicationMetadata(null);
                    defaultApplication.getPages().forEach(page -> page.setDefaultPageId(page.getId()));
                    if (!CollectionUtils.isNullOrEmpty(defaultApplication.getPublishedPages())) {
                        defaultApplication.getPublishedPages().forEach(page -> page.setDefaultPageId(page.getId()));
                    }
                    return fileUtils.deleteLocalRepo(repoSuffix).flatMap(status -> Flux.fromIterable(branch)
                            .flatMap(gitBranch -> applicationService
                                    .findByBranchNameAndDefaultApplicationId(
                                            gitBranch, defaultApplicationId, applicationPermission.getEditPermission())
                                    .flatMap(applicationPageService::deleteApplicationByResource))
                            .then(applicationService.save(defaultApplication)));
                })
                .flatMap(application ->
                        // Update all the resources to replace defaultResource Ids with the resource Ids as branchName
                        // will be deleted
                        Flux.fromIterable(application.getPages())
                                .flatMap(page -> newPageService.findById(page.getId(), Optional.empty()))
                                .map(newPage -> {
                                    newPage.setDefaultResources(null);
                                    return createDefaultIdsOrUpdateWithGivenResourceIds(newPage, null);
                                })
                                .collectList()
                                .flatMapMany(newPageService::saveAll)
                                .flatMap(newPage -> newActionService
                                        .findByPageId(newPage.getId(), Optional.empty())
                                        .map(newAction -> {
                                            newAction.setDefaultResources(null);
                                            if (newAction.getUnpublishedAction() != null) {
                                                newAction.getUnpublishedAction().setDefaultResources(null);
                                            }
                                            if (newAction.getPublishedAction() != null) {
                                                newAction.getPublishedAction().setDefaultResources(null);
                                            }
                                            return createDefaultIdsOrUpdateWithGivenResourceIds(newAction, null);
                                        })
                                        .collectList()
                                        .flatMapMany(newActionService::saveAll)
                                        .thenMany(actionCollectionService.findByPageId(newPage.getId()))
                                        .map(actionCollection -> {
                                            actionCollection.setDefaultResources(null);
                                            if (actionCollection.getUnpublishedCollection() != null) {
                                                actionCollection
                                                        .getUnpublishedCollection()
                                                        .setDefaultResources(null);
                                            }
                                            if (actionCollection.getPublishedCollection() != null) {
                                                actionCollection
                                                        .getPublishedCollection()
                                                        .setDefaultResources(null);
                                            }
                                            return createDefaultIdsOrUpdateWithGivenResourceIds(actionCollection, null);
                                        })
                                        .collectList()
                                        .flatMapMany(actionCollectionService::saveAll))
                                .then(addAnalyticsForGitOperation(AnalyticsEvents.GIT_DISCONNECT, application, false))
                                .map(responseUtils::updateApplicationWithDefaultResources))
                .name(GitSpan.OPS_DETACH_REMOTE)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> disconnectMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    public Mono<Application> createBranch(String defaultApplicationId, GitBranchDTO branchDTO, String srcBranch) {

        /*
        1. Check if the src application is available and user have sufficient permissions
        2. Create and checkout to requested branch
        3. Rehydrate the application from source application reference
         */

        if (StringUtils.isEmptyOrNull(srcBranch)
                || srcBranch.startsWith("origin/")
                || branchDTO.getBranchName().startsWith("origin/")) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        }

        Mono<Application> createBranchMono = applicationService
                .findByBranchNameAndDefaultApplicationId(
                        srcBranch, defaultApplicationId, applicationPermission.getEditPermission())
                .zipWhen(srcApplication -> {
                    GitArtifactMetadata gitData = srcApplication.getGitApplicationMetadata();
                    if (gitData.getDefaultApplicationId().equals(srcApplication.getId())) {
                        return Mono.just(
                                srcApplication.getGitApplicationMetadata().getGitAuth());
                    }
                    return applicationService
                            .getSshKey(gitData.getDefaultApplicationId())
                            .map(gitAuthDTO -> {
                                GitAuth gitAuth = new GitAuth();
                                gitAuth.setPrivateKey(gitAuthDTO.getPrivateKey());
                                gitAuth.setPublicKey(gitAuthDTO.getPublicKey());
                                gitAuth.setDocUrl(gitAuthDTO.getDocUrl());
                                return gitAuth;
                            });
                })
                .flatMap(tuple -> {
                    Application srcApplication = tuple.getT1();
                    GitAuth defaultGitAuth = tuple.getT2();
                    GitArtifactMetadata srcBranchGitData = srcApplication.getGitApplicationMetadata();
                    if (srcBranchGitData == null
                            || StringUtils.isEmptyOrNull(srcBranchGitData.getDefaultApplicationId())
                            || StringUtils.isEmptyOrNull(srcBranchGitData.getRepoName())) {
                        return Mono.error(
                                new AppsmithException(
                                        AppsmithError.INVALID_GIT_CONFIGURATION,
                                        "Unable to find the parent branch. Please create a branch from other available branches"));
                    }
                    Path repoSuffix = Paths.get(
                            srcApplication.getWorkspaceId(),
                            srcBranchGitData.getDefaultApplicationId(),
                            srcBranchGitData.getRepoName());
                    // Create a new branch from the parent checked out branch

                    return addFileLock(srcBranchGitData.getDefaultApplicationId())
                            .flatMap(status -> gitExecutor.checkoutToBranch(repoSuffix, srcBranch))
                            .onErrorResume(error -> Mono.error(new AppsmithException(
                                    AppsmithError.GIT_ACTION_FAILED, "checkout", "Unable to find " + srcBranch)))
                            .zipWhen(isCheckedOut -> gitExecutor
                                    .fetchRemote(
                                            repoSuffix,
                                            defaultGitAuth.getPublicKey(),
                                            defaultGitAuth.getPrivateKey(),
                                            false,
                                            srcBranch,
                                            true)
                                    .onErrorResume(error -> Mono.error(
                                            new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "fetch", error))))
                            .flatMap(ignore -> gitExecutor
                                    .listBranches(repoSuffix)
                                    .flatMap(branchList -> {
                                        boolean isDuplicateName = branchList.stream()
                                                // We are only supporting origin as the remote name so this is safe
                                                //  but needs to be altered if we start supporting user defined remote
                                                // names
                                                .anyMatch(branch -> branch.getBranchName()
                                                        .replaceFirst("origin/", "")
                                                        .equals(branchDTO.getBranchName()));

                                        if (isDuplicateName) {
                                            return Mono.error(new AppsmithException(
                                                    AppsmithError.DUPLICATE_KEY_USER_ERROR,
                                                    "remotes/origin/" + branchDTO.getBranchName(),
                                                    FieldName.BRANCH_NAME));
                                        }
                                        return gitExecutor.createAndCheckoutToBranch(
                                                repoSuffix, branchDTO.getBranchName());
                                    }))
                            .flatMap(branchName -> {
                                final String srcApplicationId = srcApplication.getId();
                                srcBranchGitData.setBranchName(branchName);
                                srcBranchGitData.setIsRepoPrivate(null);
                                // Save a new application in DB and update from the parent branch application
                                srcBranchGitData.setGitAuth(null);
                                srcBranchGitData.setLastCommittedAt(Instant.now());
                                srcApplication.setId(null);
                                srcApplication.setPages(null);
                                srcApplication.setPublishedPages(null);
                                srcApplication.setEditModeThemeId(null);
                                srcApplication.setPublishedModeThemeId(null);
                                srcApplication.setGitApplicationMetadata(srcBranchGitData);
                                return Mono.zip(
                                        applicationService.save(srcApplication),
                                        exportService.exportByArtifactId(
                                                srcApplicationId, VERSION_CONTROL, APPLICATION));
                            })
                            .onErrorResume(error -> Mono.error(new AppsmithException(
                                    AppsmithError.GIT_ACTION_FAILED, "branch", error.getMessage())));
                })
                .flatMap(tuple -> {
                    Application savedApplication = tuple.getT1();
                    return importService
                            .importArtifactInWorkspaceFromGit(
                                    savedApplication.getWorkspaceId(),
                                    savedApplication.getId(),
                                    tuple.getT2(),
                                    branchDTO.getBranchName())
                            .map(importableArtifact -> (Application) importableArtifact)
                            .flatMap(application -> {
                                // Commit and push for new branch created this is to avoid issues when user tries to
                                // create a
                                // new branch from uncommitted branch
                                GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                                GitCommitDTO commitDTO = new GitCommitDTO();
                                commitDTO.setCommitMessage(DEFAULT_COMMIT_MESSAGE
                                        + GitDefaultCommitMessage.BRANCH_CREATED.getReason()
                                        + gitData.getBranchName());
                                commitDTO.setDoPush(true);
                                return commitApplication(
                                                commitDTO, gitData.getDefaultApplicationId(), gitData.getBranchName())
                                        .thenReturn(application);
                            });
                })
                .flatMap(application -> releaseFileLock(
                                application.getGitApplicationMetadata().getDefaultApplicationId())
                        .then(addAnalyticsForGitOperation(
                                AnalyticsEvents.GIT_CREATE_BRANCH,
                                application,
                                application.getGitApplicationMetadata().getIsRepoPrivate())))
                .map(responseUtils::updateApplicationWithDefaultResources)
                .name(GitSpan.OPS_CREATE_BRANCH)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> createBranchMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    public Mono<Application> checkoutBranch(String defaultApplicationId, String branchName, boolean addFileLock) {

        if (StringUtils.isEmptyOrNull(branchName)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        }

        // get the root application
        Mono<Application> rootAppMono =
                getApplicationById(defaultApplicationId, applicationPermission.getEditPermission());
        if (addFileLock) {
            rootAppMono = rootAppMono.flatMap(
                    application -> addFileLock(defaultApplicationId).thenReturn(application));
        }

        // If the user is trying to check out remote branch, create a new branch if the branch does not exist already
        if (branchName.startsWith("origin/")) {
            String finalBranchName = branchName.replaceFirst("origin/", "");
            rootAppMono = rootAppMono
                    .flatMap(application -> {
                        GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                        Path repoPath = Paths.get(
                                application.getWorkspaceId(),
                                gitArtifactMetadata.getDefaultApplicationId(),
                                gitArtifactMetadata.getRepoName());
                        return gitExecutor.listBranches(repoPath);
                    })
                    .flatMap(branchList -> releaseFileLock(defaultApplicationId).thenReturn(branchList))
                    .flatMap(gitBranchDTOList -> {
                        long branchMatchCount = gitBranchDTOList.stream()
                                .filter(gitBranchDTO ->
                                        gitBranchDTO.getBranchName().equals(finalBranchName))
                                .count();
                        if (branchMatchCount == 0) {
                            return checkoutRemoteBranch(defaultApplicationId, finalBranchName);
                        } else {
                            return Mono.error(new AppsmithException(
                                    AppsmithError.GIT_ACTION_FAILED,
                                    "checkout",
                                    branchName + " already exists in local - "
                                            + branchName.replaceFirst("origin/", "")));
                        }
                    });
        } else {
            rootAppMono = rootAppMono
                    .flatMap(application -> {
                        if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                            return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                        }
                        return applicationService.findByBranchNameAndDefaultApplicationId(
                                branchName, defaultApplicationId, applicationPermission.getReadPermission());
                    })
                    .flatMap(application -> addAnalyticsForGitOperation(
                            AnalyticsEvents.GIT_CHECKOUT_BRANCH,
                            application,
                            application.getGitApplicationMetadata().getIsRepoPrivate()))
                    .map(responseUtils::updateApplicationWithDefaultResources);
        }

        return rootAppMono
                .flatMap(result -> {
                    if (addFileLock) {
                        return releaseFileLock(defaultApplicationId).thenReturn(result);
                    }
                    return Mono.just(result);
                })
                .tag(GitConstants.GitMetricConstants.CHECKOUT_REMOTE, FALSE.toString())
                .name(GitSpan.OPS_CHECKOUT_BRANCH)
                .tap(Micrometer.observation(observationRegistry))
                .onErrorResume(throwable -> {
                    return Mono.error(throwable);
                });
    }

    private Mono<Application> checkoutRemoteBranch(String defaultApplicationId, String branchName) {
        Mono<Application> checkoutRemoteBranchMono = addFileLock(defaultApplicationId)
                .flatMap(status -> getApplicationById(defaultApplicationId, applicationPermission.getEditPermission()))
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    String repoName = gitArtifactMetadata.getRepoName();
                    Path repoPath = Paths.get(application.getWorkspaceId(), defaultApplicationId, repoName);
                    return gitExecutor
                            .fetchRemote(
                                    repoPath,
                                    gitArtifactMetadata.getGitAuth().getPublicKey(),
                                    gitArtifactMetadata.getGitAuth().getPrivateKey(),
                                    false,
                                    branchName,
                                    true)
                            .flatMap(fetchStatus -> gitExecutor
                                    .checkoutRemoteBranch(repoPath, branchName)
                                    .zipWith(Mono.just(application))
                                    .onErrorResume(error -> Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED, "checkout branch", error.getMessage()))));
                })
                .flatMap(tuple -> {
                    /*
                     * create a new application(each application => git branch)
                     * Populate the application from the file system
                     * Check if the existing branch track the given remote branch using the StoredConfig
                     * Use the create branch method with isRemoteFlag or use the setStartPoint ,method in createBranch method
                     * */
                    Application srcApplication = tuple.getT2();
                    Mono<Application> applicationMono;

                    // Create a new Application
                    GitArtifactMetadata srcBranchGitData = srcApplication.getGitApplicationMetadata();
                    if (branchName.equals(srcBranchGitData.getBranchName())) {
                        /*
                         in this case, user deleted the initial default branch and now wants to check out to that branch.
                         as we didn't delete the application object but only the branch from git repo,
                         we can just use this existing application without creating a new one.
                        */
                        applicationMono = Mono.just(srcApplication);
                    } else {
                        srcBranchGitData.setBranchName(branchName);
                        srcBranchGitData.setDefaultApplicationId(defaultApplicationId);
                        // Save a new application in DB and update from the parent branch application
                        srcBranchGitData.setGitAuth(null);
                        srcBranchGitData.setIsRepoPrivate(null);
                        srcBranchGitData.setLastCommittedAt(Instant.now());
                        srcApplication.setId(null);
                        srcApplication.setPages(null);
                        srcApplication.setPublishedPages(null);
                        srcApplication.setGitApplicationMetadata(srcBranchGitData);
                        srcApplication.setEditModeThemeId(null);
                        srcApplication.setPublishedModeThemeId(null);
                        applicationMono = applicationService.save(srcApplication);
                    }

                    return applicationMono
                            .flatMap(application1 -> fileUtils
                                    .reconstructApplicationJsonFromGitRepoWithAnalytics(
                                            srcApplication.getWorkspaceId(),
                                            defaultApplicationId,
                                            srcApplication
                                                    .getGitApplicationMetadata()
                                                    .getRepoName(),
                                            branchName)
                                    .zipWith(Mono.just(application1)))
                            // We need to handle the case specifically for default branch of Appsmith
                            // if user switches default branch and tries to delete the default branch we do not delete
                            // resource from db
                            // This is an exception only for the above case and in such case if the user tries to check
                            // out the branch again
                            // It results in an error as the resources are already present in db
                            // So we just rehydrate from the file system to the existing resource on the db
                            .onErrorResume(throwable -> {
                                if (throwable instanceof DuplicateKeyException) {
                                    return fileUtils
                                            .reconstructApplicationJsonFromGitRepoWithAnalytics(
                                                    srcApplication.getWorkspaceId(),
                                                    defaultApplicationId,
                                                    srcApplication
                                                            .getGitApplicationMetadata()
                                                            .getRepoName(),
                                                    branchName)
                                            .zipWith(Mono.just(tuple.getT2()));
                                }
                                log.error(" Git checkout remote branch failed {}", throwable.getMessage());
                                return Mono.error(new AppsmithException(
                                        AppsmithError.GIT_ACTION_FAILED, " --checkout", throwable.getMessage()));
                            });
                })
                .flatMap(tuple -> {
                    // Get the latest application mono with all the changes
                    ApplicationJson applicationJson = tuple.getT1();
                    Application application = tuple.getT2();
                    return importService
                            .importArtifactInWorkspaceFromGit(
                                    application.getWorkspaceId(), application.getId(), applicationJson, branchName)
                            .map(importableArtifact -> (Application) importableArtifact)
                            .flatMap(application1 -> addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_CHECKOUT_REMOTE_BRANCH,
                                    application1,
                                    TRUE.equals(application1
                                            .getGitApplicationMetadata()
                                            .getIsRepoPrivate())))
                            .map(responseUtils::updateApplicationWithDefaultResources)
                            .flatMap(application1 ->
                                    releaseFileLock(defaultApplicationId).then(Mono.just(application1)));
                })
                .tag(GitConstants.GitMetricConstants.CHECKOUT_REMOTE, TRUE.toString())
                .name(GitSpan.OPS_CHECKOUT_BRANCH)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(
                sink -> checkoutRemoteBranchMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    private Mono<Application> publishAndOrGetApplication(String applicationId, boolean publish) {
        if (TRUE.equals(publish)) {
            return applicationPageService
                    .publish(applicationId, true)
                    // Get application here to decrypt the git private key if present
                    .then(getApplicationById(applicationId, applicationPermission.getEditPermission()));
        }
        return getApplicationById(applicationId, applicationPermission.getEditPermission());
    }

    /**
     * This method is deprecated and will be removed in next release. Please use the following method:
     * getApplicationById(String applicationId, AclPermission aclPermission)
     *
     * @param applicationId ID of the application
     * @return Mono of Application
     */
    @Deprecated
    public Mono<Application> getApplicationById(String applicationId) {
        return getApplicationById(applicationId, applicationPermission.getEditPermission());
    }

    public Mono<Application> getApplicationById(String applicationId, AclPermission aclPermission) {
        return applicationService
                .findById(applicationId, aclPermission)
                .switchIfEmpty(Mono.error(new AppsmithException(
                        AppsmithError.ACL_NO_RESOURCE_FOUND, FieldName.APPLICATION, applicationId)));
    }

    @Deprecated
    protected Mono<Workspace> checkPermissionOnWorkspace(
            String workspaceId, AclPermission aclPermission, String operationName) {
        return workspaceService
                .findById(workspaceId, aclPermission)
                .switchIfEmpty(
                        Mono.error(new AppsmithException(AppsmithError.ACTION_IS_NOT_AUTHORIZED, operationName)));
    }

    /**
     * Method to pull application json files from remote repo, make a commit with the changes present in local DB and
     * make a system commit to remote repo
     *
     * @param defaultApplicationId application for which we want to pull remote changes and merge
     * @param branchName           remoteBranch from which the changes will be pulled and merged
     * @return return the status of pull operation
     */
    @Override
    public Mono<GitPullDTO> pullApplication(String defaultApplicationId, String branchName) {
        /*
         * 1.Dehydrate the application from DB so that the file system has the latest application data
         * 2.Do git pull after the rehydration and merge the remote changes to the current branch
         *   On Merge conflict - throw exception and ask user to resolve these conflicts on remote
         *   TODO create new branch and push the changes to remote and ask the user to resolve it on github/gitlab UI
         * 3.Then rehydrate from the file system to DB so that the latest changes from remote are rendered to the application
         * 4.Get the latest application from the DB and send it back to client
         * */

        Mono<GitPullDTO> pullMono = getApplicationById(defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    return addFileLock(gitData.getDefaultApplicationId()).then(Mono.just(application));
                })
                .flatMap(defaultApplication -> {
                    GitArtifactMetadata defaultGitMetadata = defaultApplication.getGitApplicationMetadata();
                    return Mono.zip(
                            Mono.just(defaultApplication),
                            getStatus(defaultGitMetadata.getDefaultApplicationId(), branchName, false));
                })
                .flatMap(tuple -> {
                    Application defaultApplication = tuple.getT1();
                    GitStatusDTO status = tuple.getT2();
                    // Check if the repo is not clean
                    if (!status.getIsClean()) {
                        return Mono.error(
                                new AppsmithException(
                                        AppsmithError.GIT_ACTION_FAILED,
                                        "pull",
                                        "There are uncommitted changes present in your local. Please commit them first and then try git pull"));
                    }
                    return pullAndRehydrateApplication(defaultApplication, branchName)
                            // Release file lock after the pull operation
                            .flatMap(gitPullDTO ->
                                    releaseFileLock(defaultApplicationId).then(Mono.just(gitPullDTO)));
                })
                .name(GitSpan.OPS_PULL)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> pullMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    private Flux<Application> updateDefaultBranchName(
            Path repoPath, String defaultBranchName, String defaultApplicationId) {
        // Get the application from DB by new defaultBranch name
        return applicationService
                .findByBranchNameAndDefaultApplicationId(
                        defaultBranchName, defaultApplicationId, applicationPermission.getEditPermission())
                // Check if the branch is already present, If not follow checkout remote flow
                .onErrorResume(throwable -> checkoutRemoteBranch(defaultApplicationId, defaultBranchName))
                // ensure the local branch exists
                .then(gitExecutor
                        .createAndCheckoutToBranch(repoPath, defaultBranchName)
                        .onErrorComplete())
                // Update the default branch name in all the child applications
                .thenMany(applicationService
                        .findAllApplicationsByDefaultApplicationId(
                                defaultApplicationId, applicationPermission.getEditPermission())
                        .flatMap(application2 -> {
                            application2.getGitApplicationMetadata().setDefaultBranchName(defaultBranchName);
                            // clear the branch protection rules as the default branch name has been changed
                            application2.getGitApplicationMetadata().setBranchProtectionRules(null);
                            return applicationService.save(application2);
                        }));
    }

    private Mono<List<GitBranchDTO>> handleRepoNotFoundException(String defaultApplicationId) {

        // clone application to the local filesystem again and update the defaultBranch for the application
        // list branch and compare with branch applications and checkout if not exists

        return getApplicationById(defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    Path repoPath = Paths.get(
                            application.getWorkspaceId(), application.getId(), gitArtifactMetadata.getRepoName());
                    GitAuth gitAuth = gitArtifactMetadata.getGitAuth();
                    return gitExecutor
                            .cloneRemoteIntoArtifactRepo(
                                    repoPath,
                                    gitArtifactMetadata.getRemoteUrl(),
                                    gitAuth.getPrivateKey(),
                                    gitAuth.getPublicKey())
                            .flatMap(defaultBranch -> gitExecutor.listBranches(repoPath))
                            .flatMap(gitBranchDTOList -> {
                                List<String> branchesToCheckout = new ArrayList<>();
                                for (GitBranchDTO gitBranchDTO : gitBranchDTOList) {
                                    if (gitBranchDTO.getBranchName().startsWith("origin/")) {
                                        // remove origin/ prefix from the remote branch name
                                        String branchName =
                                                gitBranchDTO.getBranchName().replace("origin/", "");
                                        // The root application is always there, no need to check out it again
                                        if (!branchName.equals(gitArtifactMetadata.getBranchName())) {
                                            branchesToCheckout.add(branchName);
                                        }
                                    } else if (gitBranchDTO
                                            .getBranchName()
                                            .equals(gitArtifactMetadata.getDefaultBranchName())) {
                                        /*
                                         We just cloned from the remote default branch.
                                         Update the isDefault flag If it's also set as default in DB
                                        */
                                        gitBranchDTO.setDefault(true);
                                    }
                                }

                                return Flux.fromIterable(branchesToCheckout)
                                        .flatMap(branchName -> applicationService
                                                .findByBranchNameAndDefaultApplicationId(
                                                        branchName,
                                                        application.getId(),
                                                        applicationPermission.getReadPermission())
                                                // checkout the branch locally
                                                .flatMap(application1 -> {
                                                    // Add the locally checked out branch to the branchList
                                                    GitBranchDTO gitBranchDTO = new GitBranchDTO();
                                                    gitBranchDTO.setBranchName(branchName);
                                                    // set the default branch flag if there's a match.
                                                    // This can happen when user has changed the default branch other
                                                    // than
                                                    // remote
                                                    gitBranchDTO.setDefault(gitArtifactMetadata
                                                            .getDefaultBranchName()
                                                            .equals(branchName));
                                                    gitBranchDTOList.add(gitBranchDTO);
                                                    return gitExecutor.checkoutRemoteBranch(repoPath, branchName);
                                                })
                                                // Return empty mono when the branched application is not in db
                                                .onErrorResume(throwable -> Mono.empty()))
                                        .then(Mono.just(gitBranchDTOList));
                            });
                });
    }

    private Mono<String> syncDefaultBranchNameFromRemote(Path repoPath, Application rootApp) {
        GitArtifactMetadata metadata = rootApp.getGitApplicationMetadata();
        GitAuth gitAuth = metadata.getGitAuth();
        return addFileLock(metadata.getDefaultApplicationId())
                .then(gitExecutor.getRemoteDefaultBranch(
                        repoPath, metadata.getRemoteUrl(), gitAuth.getPrivateKey(), gitAuth.getPublicKey()))
                .flatMap(defaultBranchNameInRemote -> {
                    String defaultBranchInDb = GitUtils.getDefaultBranchName(metadata);
                    if (StringUtils.isEmptyOrNull(defaultBranchNameInRemote)) {
                        // If the default branch name in remote is empty or same as the one in DB, nothing to do
                        return Mono.just(defaultBranchInDb);
                    }
                    // check if default branch has been changed in remote
                    if (defaultBranchNameInRemote.equals(defaultBranchInDb)) {
                        return Mono.just(defaultBranchNameInRemote);
                    }
                    return updateDefaultBranchName(
                                    repoPath, defaultBranchNameInRemote, metadata.getDefaultApplicationId())
                            .then()
                            .thenReturn(defaultBranchNameInRemote);
                })
                .flatMap(branchName ->
                        releaseFileLock(metadata.getDefaultApplicationId()).thenReturn(branchName));
    }

    @Override
    public Mono<List<GitBranchDTO>> listBranchForApplication(
            String defaultApplicationId, Boolean pruneBranches, String currentBranch) {
        return getBranchList(defaultApplicationId, pruneBranches, currentBranch, true);
    }

    protected Mono<List<GitBranchDTO>> getBranchList(
            String defaultApplicationId,
            Boolean pruneBranches,
            String currentBranch,
            boolean syncDefaultBranchWithRemote) {
        // get the root application
        Mono<List<GitBranchDTO>> branchMono = getApplicationById(
                        defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(rootApplication -> {
                    Path repoPath = getRepoPath(rootApplication);
                    Mono<String> defaultBranchMono;
                    if (TRUE.equals(pruneBranches) && syncDefaultBranchWithRemote) {
                        defaultBranchMono = syncDefaultBranchNameFromRemote(repoPath, rootApplication);
                    } else {
                        defaultBranchMono =
                                Mono.just(GitUtils.getDefaultBranchName(rootApplication.getGitApplicationMetadata()));
                    }
                    return Mono.zip(defaultBranchMono, Mono.just(rootApplication), Mono.just(repoPath));
                })
                .flatMap(objects -> {
                    String defaultBranchName = objects.getT1();
                    Application rootApplication = objects.getT2();
                    Path repoPath = objects.getT3();
                    return getBranchListWithDefaultBranchName(
                            rootApplication, repoPath, defaultBranchName, currentBranch, pruneBranches);
                })
                .onErrorResume(throwable -> {
                    if (throwable instanceof RepositoryNotFoundException) {
                        // this will clone the repo again
                        return handleRepoNotFoundException(defaultApplicationId);
                    }
                    return Mono.error(throwable);
                });

        return Mono.create(sink -> branchMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    private Path getRepoPath(Application rootApplication) {
        GitArtifactMetadata gitArtifactMetadata = rootApplication.getGitApplicationMetadata();
        if (gitArtifactMetadata == null
                || gitArtifactMetadata.getDefaultApplicationId() == null
                || gitArtifactMetadata.getRepoName() == null) {
            log.error("Git config is not present for application {}", rootApplication.getId());
            throw new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR);
        }
        return Paths.get(
                rootApplication.getWorkspaceId(),
                gitArtifactMetadata.getDefaultApplicationId(),
                gitArtifactMetadata.getRepoName());
    }

    private Mono<List<GitBranchDTO>> getBranchListWithDefaultBranchName(
            Application rootApp, Path repoPath, String defaultBranchName, String currentBranch, boolean pruneBranches) {
        return addFileLock(rootApp.getId())
                .flatMap(objects -> {
                    GitArtifactMetadata gitArtifactMetadata = rootApp.getGitApplicationMetadata();

                    if (TRUE.equals(pruneBranches)) {
                        return gitExecutor
                                .fetchRemote(
                                        repoPath,
                                        gitArtifactMetadata.getGitAuth().getPublicKey(),
                                        gitArtifactMetadata.getGitAuth().getPrivateKey(),
                                        false,
                                        currentBranch,
                                        true)
                                .then(gitExecutor.listBranches(repoPath));
                    } else {
                        return gitExecutor.listBranches(repoPath);
                    }
                })
                .flatMap(branchDTOList -> releaseFileLock(rootApp.getId()).thenReturn(branchDTOList))
                .map(branchDTOList -> {
                    for (GitBranchDTO branchDTO : branchDTOList) {
                        if (StringUtils.equalsIgnoreCase(branchDTO.getBranchName(), defaultBranchName)) {
                            branchDTO.setDefault(true);
                            break;
                        }
                    }
                    return branchDTOList;
                })
                .flatMap(gitBranchDTOList -> FALSE.equals(pruneBranches)
                        ? Mono.just(gitBranchDTOList)
                        : addAnalyticsForGitOperation(
                                        AnalyticsEvents.GIT_PRUNE,
                                        rootApp,
                                        rootApp.getGitApplicationMetadata().getIsRepoPrivate())
                                .thenReturn(gitBranchDTOList));
    }

    private Mono<GitStatusDTO> getStatus(String defaultApplicationId, String branchName, boolean isFileLock) {
        return getStatus(defaultApplicationId, branchName, isFileLock, true);
    }

    /**
     * Get the status of the mentioned branch
     *
     * @param defaultApplicationId root/default application
     * @param branchName           for which the status is required
     * @param isFileLock           if the locking is required, since the status API is used in the other flows of git
     *                             Only for the direct hits from the client the locking will be added
     * @return Map of json file names which are added, modified, conflicting, removed and the working tree if this is clean
     */
    private Mono<GitStatusDTO> getStatus(
            String defaultApplicationId, String branchName, boolean isFileLock, boolean compareRemote) {

        if (StringUtils.isEmptyOrNull(branchName)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        }

        final String finalBranchName = branchName.replaceFirst("origin/", "");
        Mono<Application> branchedAppMono = applicationService
                .findByBranchNameAndDefaultApplicationId(
                        finalBranchName, defaultApplicationId, applicationPermission.getEditPermission())
                .onErrorResume(error -> {
                    // if the branch does not exist in local, checkout remote branch
                    // Why would we require this is another question
                    return Mono.defer(() -> checkoutBranch(defaultApplicationId, finalBranchName, false));
                })
                .switchIfEmpty(Mono.error(new AppsmithException(AppsmithError.GIT_GENERIC_ERROR)))
                .cache();

        /*
           1. Copy resources from DB to local repo
           2. Fetch the current status from local repo
        */
        Mono<GitStatusDTO> statusMono = getGitApplicationMetadata(defaultApplicationId)
                .zipWhen(gitArtifactMetadata -> branchedAppMono)
                .flatMap(tuple2 -> {
                    GitArtifactMetadata gitArtifactMetadata = tuple2.getT1();
                    Application branchedApplication = tuple2.getT2();
                    Mono<ApplicationJson> exportAppMono = exportService
                            .exportByArtifactId(branchedApplication.getId(), VERSION_CONTROL, APPLICATION)
                            .map(artifactExchangeJson -> (ApplicationJson) artifactExchangeJson);

                    return Mono.zip(Mono.just(gitArtifactMetadata), Mono.just(branchedApplication), exportAppMono);
                })
                .flatMap(tuple3 -> {
                    Mono<Boolean> fileLockMono = Mono.empty();
                    if (isFileLock) {
                        fileLockMono = Mono.defer(() -> addFileLock(defaultApplicationId));
                    }

                    return fileLockMono.thenReturn(tuple3);
                })
                .flatMap(tuple3 -> {
                    GitArtifactMetadata defaultApplicationMetadata = tuple3.getT1();
                    Application application = tuple3.getT2();
                    ApplicationJson applicationJson = tuple3.getT3();

                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    gitData.setGitAuth(defaultApplicationMetadata.getGitAuth());
                    Path repoSuffix = Paths.get(
                            application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

                    try {
                        // Create a Mono to fetch the status from remote
                        Path repoSuffixForFetchRemote = Paths.get(
                                application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());
                        GitAuth gitAuth = gitData.getGitAuth();
                        Mono<String> fetchRemoteMono;

                        if (compareRemote) {
                            fetchRemoteMono = Mono.defer(() -> gitExecutor.fetchRemote(
                                            repoSuffixForFetchRemote,
                                            gitAuth.getPublicKey(),
                                            gitAuth.getPrivateKey(),
                                            false,
                                            branchName,
                                            false))
                                    .onErrorResume(error -> Mono.error(new AppsmithException(
                                            AppsmithError.GIT_GENERIC_ERROR, error.getMessage())));
                        } else {
                            fetchRemoteMono = Mono.just("ignored");
                        }
                        return Mono.zip(
                                fileUtils.saveApplicationToLocalRepoWithAnalytics(
                                        repoSuffix, applicationJson, finalBranchName),
                                Mono.just(repoSuffix),
                                fetchRemoteMono);
                    } catch (IOException | GitAPIException e) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", e.getMessage()));
                    }
                })
                .flatMap(tuple3 -> {
                    return gitExecutor
                            .getStatus(tuple3.getT1(), finalBranchName)
                            .flatMap(result -> {
                                // Remove any files which are copied by hard resetting the repo
                                try {
                                    return gitExecutor
                                            .resetToLastCommit(tuple3.getT2(), branchName)
                                            .thenReturn(result);
                                } catch (Exception e) {
                                    log.error(
                                            "failed to reset to last commit for application: {}, branch: {}",
                                            defaultApplicationId,
                                            branchName,
                                            e);
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED, "status", e.getMessage()));
                                }
                            });
                })
                .flatMap(result -> {
                    // release the lock if there's a successful response
                    if (isFileLock) {
                        return releaseFileLock(defaultApplicationId).thenReturn(result);
                    }
                    return Mono.just(result);
                })
                .onErrorResume(throwable -> {
                    /*
                     in case of any error, the global exception handler will release the lock
                     hence we don't need to do that manually
                    */
                    log.error(
                            "Error to get status for application: {}, branch: {}",
                            defaultApplicationId,
                            branchName,
                            throwable);
                    return Mono.error(new AppsmithException(AppsmithError.GIT_GENERIC_ERROR, throwable.getMessage()));
                })
                .name(OPS_STATUS)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.zip(statusMono, sessionUserService.getCurrentUser(), branchedAppMono)
                .elapsed()
                .flatMap(objects -> {
                    Long elapsedTime = objects.getT1();
                    GitStatusDTO gitStatusDTO = objects.getT2().getT1();
                    User currentUser = objects.getT2().getT2();
                    Application app = objects.getT2().getT3();
                    String flowName;
                    if (compareRemote) {
                        flowName = AnalyticsEvents.GIT_STATUS.getEventName();
                    } else {
                        flowName = AnalyticsEvents.GIT_STATUS_WITHOUT_FETCH.getEventName();
                    }
                    return sendUnitExecutionTimeAnalyticsEvent(flowName, elapsedTime, currentUser, app)
                            .thenReturn(gitStatusDTO);
                });
    }

    private Mono<Void> sendUnitExecutionTimeAnalyticsEvent(
            String flowName, Long elapsedTime, User currentUser, Application app) {
        final Map<String, Object> data = Map.of(
                FieldName.FLOW_NAME,
                flowName,
                FieldName.APPLICATION_ID,
                app.getGitApplicationMetadata().getDefaultApplicationId(),
                "appId",
                app.getGitApplicationMetadata().getDefaultApplicationId(),
                FieldName.BRANCH_NAME,
                app.getGitApplicationMetadata().getBranchName(),
                "organizationId",
                app.getWorkspaceId(),
                "repoUrl",
                app.getGitApplicationMetadata().getRemoteUrl(),
                "executionTime",
                elapsedTime);
        return analyticsService.sendEvent(
                AnalyticsEvents.UNIT_EXECUTION_TIME.getEventName(), currentUser.getUsername(), data);
    }

    @Override
    public Mono<GitStatusDTO> getStatus(String defaultApplicationId, boolean compareRemote, String branchName) {
        return getStatus(defaultApplicationId, branchName, true, compareRemote);
    }

    /**
     * This method is responsible to compare the current branch with the remote branch.
     * Comparing means finding two numbers - how many commits ahead and behind the local branch is.
     * It'll do the following things -
     * 1. Checkout (if required) to the branch to make sure we are comparing the right branch
     * 2. Run a git fetch command to fetch the latest changes from the remote
     *
     * @param defaultApplicationId Default application id
     * @param branchName           name of the branch to compare with remote
     * @param isFileLock           whether to add file lock or not
     * @return Mono of {@link BranchTrackingStatus}
     */
    @Override
    public Mono<BranchTrackingStatus> fetchRemoteChanges(
            String defaultApplicationId, String branchName, boolean isFileLock) {
        if (StringUtils.isEmptyOrNull(branchName)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        }
        final String finalBranchName = branchName.replaceFirst("origin/", "");

        Mono<Application> applicationMono = applicationService
                .findByBranchNameAndDefaultApplicationId(
                        finalBranchName, defaultApplicationId, applicationPermission.getEditPermission())
                .cache(); // caching as it'll be also used when sending analytics
        Mono<User> currUserMono = sessionUserService.getCurrentUser(); // will be used to send analytics event
        Mono<BranchTrackingStatus> fetchRemoteStatusMono = getGitApplicationMetadata(defaultApplicationId)
                .flatMap(gitApplicationMetadata -> {
                    if (isFileLock) {
                        // Add file lock to avoid sending wrong info on the status
                        return addFileLock(gitApplicationMetadata.getDefaultApplicationId())
                                .then(Mono.zip(Mono.just(gitApplicationMetadata), applicationMono));
                    }
                    return Mono.zip(Mono.just(gitApplicationMetadata), applicationMono);
                })
                .flatMap(tuple -> {
                    GitArtifactMetadata defaultApplicationMetadata = tuple.getT1();
                    Application application = tuple.getT2();
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    gitData.setGitAuth(defaultApplicationMetadata.getGitAuth());
                    Path repoSuffix = Paths.get(
                            application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());
                    Path repoPath = gitExecutor.createRepoPath(repoSuffix);

                    Mono<Boolean> checkoutBranchMono = gitExecutor.checkoutToBranch(repoSuffix, finalBranchName);
                    Mono<String> fetchRemoteMono = gitExecutor.fetchRemote(
                            repoPath,
                            gitData.getGitAuth().getPublicKey(),
                            gitData.getGitAuth().getPrivateKey(),
                            true,
                            finalBranchName,
                            false);
                    Mono<BranchTrackingStatus> branchedStatusMono =
                            gitExecutor.getBranchTrackingStatus(repoPath, finalBranchName);

                    return checkoutBranchMono
                            .then(fetchRemoteMono)
                            .then(branchedStatusMono)
                            .flatMap(branchTrackingStatus -> {
                                if (isFileLock) {
                                    return releaseFileLock(defaultApplicationId).thenReturn(branchTrackingStatus);
                                }
                                return Mono.just(branchTrackingStatus);
                            })
                            .onErrorResume(throwable -> {
                                /*
                                 in case of any error, the global exception handler will release the lock
                                 hence we don't need to do that manually
                                */
                                log.error(
                                        "Error to fetch from remote for application: {}, branch: {}",
                                        defaultApplicationId,
                                        branchName,
                                        throwable);
                                return Mono.error(new AppsmithException(
                                        AppsmithError.GIT_ACTION_FAILED, "fetch", throwable.getMessage()));
                            });
                })
                .elapsed()
                .zipWith(Mono.zip(currUserMono, applicationMono))
                .flatMap(objects -> {
                    Long elapsedTime = objects.getT1().getT1();
                    BranchTrackingStatus branchTrackingStatus = objects.getT1().getT2();
                    User currentUser = objects.getT2().getT1();
                    Application app = objects.getT2().getT2();
                    return sendUnitExecutionTimeAnalyticsEvent(
                                    AnalyticsEvents.GIT_FETCH.getEventName(), elapsedTime, currentUser, app)
                            .thenReturn(branchTrackingStatus);
                })
                .name(GitSpan.OPS_FETCH_REMOTE)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> {
            fetchRemoteStatusMono.subscribe(sink::success, sink::error, null, sink.currentContext());
        });
    }

    @Override
    public Mono<MergeStatusDTO> mergeBranch(String defaultApplicationId, GitMergeDTO gitMergeDTO) {
        /*
         * 1.Dehydrate the application from Mongodb so that the file system has the latest application data for both the source and destination branch application
         * 2.Do git checkout destinationBranch ---> git merge sourceBranch after the rehydration
         *   On Merge conflict - create new branch and push the changes to remote and ask the user to resolve it on Github/Gitlab UI
         * 3.Then rehydrate from the file system to mongodb so that the latest changes from remote are rendered to the application
         * 4.Get the latest application mono from the mongodb and send it back to client
         * */

        final String sourceBranch = gitMergeDTO.getSourceBranch();
        final String destinationBranch = gitMergeDTO.getDestinationBranch();

        if (StringUtils.isEmptyOrNull(sourceBranch) || StringUtils.isEmptyOrNull(destinationBranch)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        } else if (sourceBranch.startsWith("origin/")) {
            return Mono.error(
                    new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION_FOR_REMOTE_BRANCH, sourceBranch));
        } else if (destinationBranch.startsWith("origin/")) {
            return Mono.error(
                    new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION_FOR_REMOTE_BRANCH, destinationBranch));
        }

        Mono<MergeStatusDTO> mergeMono = getApplicationById(
                        defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    return addFileLock(gitData.getDefaultApplicationId()).then(Mono.just(application));
                })
                .flatMap(defaultApplication -> {
                    GitArtifactMetadata gitArtifactMetadata = defaultApplication.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(defaultApplication.getGitApplicationMetadata())) {
                        return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                    }
                    Path repoSuffix = Paths.get(
                            defaultApplication.getWorkspaceId(),
                            gitArtifactMetadata.getDefaultApplicationId(),
                            gitArtifactMetadata.getRepoName());

                    // 1. Hydrate from db to file system for both branch Applications
                    Mono<Path> pathToFile = this.getStatus(defaultApplicationId, sourceBranch, false)
                            .flatMap(status -> {
                                if (!Integer.valueOf(0).equals(status.getBehindCount())) {
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES,
                                            status.getBehindCount(),
                                            sourceBranch));
                                } else if (!status.getIsClean()) {
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES, sourceBranch));
                                }
                                return this.getStatus(defaultApplicationId, destinationBranch, false)
                                        .map(status1 -> {
                                            if (!Integer.valueOf(0).equals(status.getBehindCount())) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES,
                                                        status.getBehindCount(),
                                                        destinationBranch));
                                            } else if (!status.getIsClean()) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES,
                                                        destinationBranch));
                                            }
                                            return status1;
                                        });
                            })
                            .thenReturn(repoSuffix);

                    return Mono.zip(Mono.just(defaultApplication), pathToFile).onErrorResume(error -> {
                        log.error("Error in repo status check for application " + defaultApplicationId, error);
                        if (error instanceof AppsmithException) {
                            return Mono.error(error);
                        }
                        return Mono.error(new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", error));
                    });
                })
                .flatMap(tuple -> {
                    Application defaultApplication = tuple.getT1();
                    Path repoSuffix = tuple.getT2();

                    // 2. git checkout destinationBranch ---> git merge sourceBranch
                    return Mono.zip(
                                    gitExecutor.mergeBranch(repoSuffix, sourceBranch, destinationBranch),
                                    Mono.just(defaultApplication))
                            .onErrorResume(error -> addAnalyticsForGitOperation(
                                            AnalyticsEvents.GIT_MERGE,
                                            defaultApplication,
                                            error.getClass().getName(),
                                            error.getMessage(),
                                            defaultApplication
                                                    .getGitApplicationMetadata()
                                                    .getIsRepoPrivate())
                                    .flatMap(application -> {
                                        if (error instanceof GitAPIException) {
                                            return Mono.error(new AppsmithException(
                                                    AppsmithError.GIT_MERGE_CONFLICTS, error.getMessage()));
                                        }
                                        return Mono.error(new AppsmithException(
                                                AppsmithError.GIT_ACTION_FAILED, "merge", error.getMessage()));
                                    }));
                })
                .flatMap(mergeStatusTuple -> {
                    Application defaultApplication = mergeStatusTuple.getT2();
                    String mergeStatus = mergeStatusTuple.getT1();

                    // 3. rehydrate from file system to db
                    Mono<ApplicationJson> applicationJson =
                            fileUtils.reconstructApplicationJsonFromGitRepoWithAnalytics(
                                    defaultApplication.getWorkspaceId(),
                                    defaultApplication
                                            .getGitApplicationMetadata()
                                            .getDefaultApplicationId(),
                                    defaultApplication
                                            .getGitApplicationMetadata()
                                            .getRepoName(),
                                    destinationBranch);
                    return Mono.zip(
                            Mono.just(mergeStatus),
                            applicationService.findByBranchNameAndDefaultApplicationId(
                                    destinationBranch, defaultApplicationId, applicationPermission.getEditPermission()),
                            applicationJson);
                })
                .flatMap(tuple -> {
                    Application destApplication = tuple.getT2();
                    ApplicationJson applicationJson = tuple.getT3();
                    MergeStatusDTO mergeStatusDTO = new MergeStatusDTO();
                    mergeStatusDTO.setStatus(tuple.getT1());
                    mergeStatusDTO.setMergeAble(TRUE);

                    // 4. Get the latest application mono with all the changes
                    return importService
                            .importArtifactInWorkspaceFromGit(
                                    destApplication.getWorkspaceId(),
                                    destApplication.getId(),
                                    applicationJson,
                                    destinationBranch.replaceFirst("origin/", ""))
                            .map(importableArtifact -> (Application) importableArtifact)
                            .flatMap(application1 -> {
                                GitCommitDTO commitDTO = new GitCommitDTO();
                                commitDTO.setDoPush(true);
                                commitDTO.setCommitMessage(DEFAULT_COMMIT_MESSAGE
                                        + GitDefaultCommitMessage.SYNC_REMOTE_AFTER_MERGE.getReason()
                                        + sourceBranch);
                                return this.commitApplication(commitDTO, defaultApplicationId, destinationBranch)
                                        .map(commitStatus -> mergeStatusDTO)
                                        .zipWith(Mono.just(application1));
                            });
                })
                .flatMap(tuple -> {
                    MergeStatusDTO mergeStatusDTO = tuple.getT1();
                    Application application = tuple.getT2();
                    // Send analytics event
                    return releaseFileLock(defaultApplicationId).flatMap(status -> addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_MERGE,
                                    application,
                                    application.getGitApplicationMetadata().getIsRepoPrivate())
                            .thenReturn(mergeStatusDTO));
                });

        return Mono.create(sink -> mergeMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<MergeStatusDTO> isBranchMergeable(String defaultApplicationId, GitMergeDTO gitMergeDTO) {

        final String sourceBranch = gitMergeDTO.getSourceBranch();
        final String destinationBranch = gitMergeDTO.getDestinationBranch();

        if (StringUtils.isEmptyOrNull(sourceBranch) || StringUtils.isEmptyOrNull(destinationBranch)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        } else if (sourceBranch.startsWith("origin/")) {
            return Mono.error(
                    new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION_FOR_REMOTE_BRANCH, sourceBranch));
        } else if (destinationBranch.startsWith("origin/")) {
            return Mono.error(
                    new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION_FOR_REMOTE_BRANCH, destinationBranch));
        }

        Mono<MergeStatusDTO> mergeableStatusMono = getApplicationById(
                        defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(application.getGitApplicationMetadata())) {
                        return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                    }
                    Path repoSuffix = Paths.get(
                            application.getWorkspaceId(),
                            gitArtifactMetadata.getDefaultApplicationId(),
                            gitArtifactMetadata.getRepoName());

                    // 1. Hydrate from db to file system for both branch Applications
                    // Update function call
                    return addFileLock(defaultApplicationId)
                            .flatMap(status -> this.getStatus(defaultApplicationId, sourceBranch, false))
                            .flatMap(srcBranchStatus -> {
                                if (!Integer.valueOf(0).equals(srcBranchStatus.getBehindCount())) {
                                    return addAnalyticsForGitOperation(
                                                    AnalyticsEvents.GIT_MERGE_CHECK,
                                                    application,
                                                    AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.name(),
                                                    AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.getMessage(
                                                            srcBranchStatus.getBehindCount(), destinationBranch),
                                                    application
                                                            .getGitApplicationMetadata()
                                                            .getIsRepoPrivate(),
                                                    false,
                                                    false)
                                            .then(Mono.error(Exceptions.propagate(new AppsmithException(
                                                    AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES,
                                                    srcBranchStatus.getBehindCount(),
                                                    sourceBranch))));
                                } else if (!srcBranchStatus.getIsClean()) {
                                    return addAnalyticsForGitOperation(
                                                    AnalyticsEvents.GIT_MERGE_CHECK,
                                                    application,
                                                    AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.name(),
                                                    AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.getMessage(
                                                            destinationBranch),
                                                    application
                                                            .getGitApplicationMetadata()
                                                            .getIsRepoPrivate(),
                                                    false,
                                                    false)
                                            .then(Mono.error(Exceptions.propagate(new AppsmithException(
                                                    AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES, sourceBranch))));
                                }
                                return this.getStatus(defaultApplicationId, destinationBranch, false)
                                        .map(destBranchStatus -> {
                                            if (!Integer.valueOf(0).equals(destBranchStatus.getBehindCount())) {
                                                return addAnalyticsForGitOperation(
                                                                AnalyticsEvents.GIT_MERGE_CHECK,
                                                                application,
                                                                AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES.name(),
                                                                AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES
                                                                        .getMessage(
                                                                                destBranchStatus.getBehindCount(),
                                                                                destinationBranch),
                                                                application
                                                                        .getGitApplicationMetadata()
                                                                        .getIsRepoPrivate(),
                                                                false,
                                                                false)
                                                        .then(Mono.error(Exceptions.propagate(new AppsmithException(
                                                                AppsmithError.GIT_MERGE_FAILED_REMOTE_CHANGES,
                                                                destBranchStatus.getBehindCount(),
                                                                destinationBranch))));
                                            } else if (!destBranchStatus.getIsClean()) {
                                                return addAnalyticsForGitOperation(
                                                                AnalyticsEvents.GIT_MERGE_CHECK,
                                                                application,
                                                                AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.name(),
                                                                AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES.getMessage(
                                                                        destinationBranch),
                                                                application
                                                                        .getGitApplicationMetadata()
                                                                        .getIsRepoPrivate(),
                                                                false,
                                                                false)
                                                        .then(Mono.error(Exceptions.propagate(new AppsmithException(
                                                                AppsmithError.GIT_MERGE_FAILED_LOCAL_CHANGES,
                                                                destinationBranch))));
                                            }
                                            return destBranchStatus;
                                        });
                            })
                            .onErrorResume(error -> {
                                log.error("Error in merge status check application " + defaultApplicationId, error);
                                if (error instanceof AppsmithException) {
                                    return Mono.error(error);
                                }
                                return Mono.error(
                                        new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "status", error));
                            })
                            .then(gitExecutor
                                    .isMergeBranch(repoSuffix, sourceBranch, destinationBranch)
                                    .flatMap(mergeStatusDTO -> releaseFileLock(defaultApplicationId)
                                            .flatMap(mergeStatus -> addAnalyticsForGitOperation(
                                                    AnalyticsEvents.GIT_MERGE_CHECK,
                                                    application,
                                                    null,
                                                    null,
                                                    application
                                                            .getGitApplicationMetadata()
                                                            .getIsRepoPrivate(),
                                                    false,
                                                    mergeStatusDTO.isMergeAble()))
                                            .then(Mono.just(mergeStatusDTO))))
                            .onErrorResume(error -> {
                                try {
                                    return gitExecutor
                                            .resetToLastCommit(repoSuffix, destinationBranch)
                                            .map(reset -> {
                                                MergeStatusDTO mergeStatus = new MergeStatusDTO();
                                                mergeStatus.setMergeAble(false);
                                                mergeStatus.setStatus("Merge check failed!");
                                                mergeStatus.setMessage(error.getMessage());
                                                if (error instanceof CheckoutConflictException) {
                                                    mergeStatus.setConflictingFiles(
                                                            ((CheckoutConflictException) error).getConflictingPaths());
                                                }
                                                mergeStatus.setReferenceDoc(
                                                        ErrorReferenceDocUrl.GIT_MERGE_CONFLICT.getDocUrl());
                                                return mergeStatus;
                                            })
                                            .flatMap(mergeStatusDTO -> addAnalyticsForGitOperation(
                                                            AnalyticsEvents.GIT_MERGE_CHECK,
                                                            application,
                                                            error.getClass().getName(),
                                                            error.getMessage(),
                                                            application
                                                                    .getGitApplicationMetadata()
                                                                    .getIsRepoPrivate(),
                                                            false,
                                                            false)
                                                    .map(application1 -> mergeStatusDTO));
                                } catch (GitAPIException | IOException e) {
                                    log.error("Error while resetting to last commit", e);
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED, "reset --hard HEAD", e.getMessage()));
                                }
                            });
                });

        return Mono.create(
                sink -> mergeableStatusMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<String> createConflictedBranch(String defaultApplicationId, String branchName) {
        if (StringUtils.isEmptyOrNull(branchName)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.BRANCH_NAME));
        }

        Mono<String> conflictedBranchMono = Mono.zip(
                        getGitApplicationMetadata(defaultApplicationId),
                        applicationService
                                .findByBranchNameAndDefaultApplicationId(
                                        branchName, defaultApplicationId, applicationPermission.getEditPermission())
                                .zipWhen(application -> exportService
                                        .exportByArtifactId(application.getId(), VERSION_CONTROL, APPLICATION)
                                        .map(artifactExchangeJson -> (ApplicationJson) artifactExchangeJson)))
                .flatMap(tuple -> {
                    GitArtifactMetadata defaultApplicationMetadata = tuple.getT1();
                    Application application = tuple.getT2().getT1();
                    ApplicationJson applicationJson = tuple.getT2().getT2();
                    GitArtifactMetadata gitData = application.getGitApplicationMetadata();
                    gitData.setGitAuth(defaultApplicationMetadata.getGitAuth());
                    Path repoSuffix = Paths.get(
                            application.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

                    try {
                        return Mono.zip(
                                fileUtils.saveApplicationToLocalRepoWithAnalytics(
                                        repoSuffix, applicationJson, branchName),
                                Mono.just(gitData),
                                Mono.just(repoSuffix));
                    } catch (IOException | GitAPIException e) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "checkout", e.getMessage()));
                    }
                })
                .flatMap(tuple -> {
                    GitArtifactMetadata gitData = tuple.getT2();
                    Path repoSuffix = tuple.getT3();
                    return gitExecutor
                            .createAndCheckoutToBranch(repoSuffix, branchName + MERGE_CONFLICT_BRANCH_NAME)
                            .flatMap(conflictedBranchName -> commitAndPushWithDefaultCommit(
                                            repoSuffix,
                                            gitData.getGitAuth(),
                                            gitData,
                                            GitDefaultCommitMessage.CONFLICT_STATE)
                                    .flatMap(successMessage -> gitExecutor.checkoutToBranch(repoSuffix, branchName))
                                    .flatMap(isCheckedOut -> gitExecutor.deleteBranch(repoSuffix, conflictedBranchName))
                                    .thenReturn(conflictedBranchName + CONFLICTED_SUCCESS_MESSAGE));
                });

        return Mono.create(
                sink -> conflictedBranchMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<ApplicationImportDTO> importApplicationFromGit(String workspaceId, GitConnectDTO gitConnectDTO) {
        // 1. Check private repo limit for workspace
        // 2. Create dummy application, clone repo from remote
        // 3. Re-hydrate application to DB from local repo
        //    1. Save the ssh keys in application object with other details
        //    2. During import-export need to handle the DS(empty vs non-empty)
        // 4. Return application

        if (StringUtils.isEmptyOrNull(gitConnectDTO.getRemoteUrl())) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Remote Url"));
        }

        if (StringUtils.isEmptyOrNull(workspaceId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, "Invalid workspace id"));
        }

        Mono<Workspace> workspaceMono = workspaceService
                .findById(workspaceId, AclPermission.WORKSPACE_CREATE_APPLICATION)
                .switchIfEmpty(Mono.error(
                        new AppsmithException(AppsmithError.NO_RESOURCE_FOUND, FieldName.WORKSPACE, workspaceId)));

        final String repoName = GitUtils.getRepoName(gitConnectDTO.getRemoteUrl());
        Mono<Boolean> isPrivateRepoMono = GitUtils.isRepoPrivate(
                        GitUtils.convertSshUrlToBrowserSupportedUrl(gitConnectDTO.getRemoteUrl()))
                .cache();
        Mono<ApplicationImportDTO> importedApplicationMono = workspaceMono
                .then(getSSHKeyForCurrentUser())
                .zipWith(isPrivateRepoMono)
                .switchIfEmpty(
                        Mono.error(
                                new AppsmithException(
                                        AppsmithError.INVALID_GIT_CONFIGURATION,
                                        "Unable to find git configuration for logged-in user. Please contact Appsmith team for support")))
                // Check the limit for number of private repo
                .flatMap(tuple -> {
                    // Check if the repo is public
                    Application newApplication = new Application();
                    newApplication.setName(repoName);
                    newApplication.setWorkspaceId(workspaceId);
                    newApplication.setGitApplicationMetadata(new GitArtifactMetadata());
                    GitAuth gitAuth = tuple.getT1();
                    boolean isRepoPrivate = tuple.getT2();
                    Mono<Application> applicationMono = applicationPageService.createOrUpdateSuffixedApplication(
                            newApplication, newApplication.getName(), 0);
                    if (!isRepoPrivate) {
                        return Mono.just(gitAuth).zipWith(applicationMono);
                    }
                    return gitPrivateRepoHelper
                            .isRepoLimitReached(workspaceId, true)
                            .flatMap(isRepoLimitReached -> {
                                if (FALSE.equals(isRepoLimitReached)) {
                                    return Mono.just(gitAuth).zipWith(applicationMono);
                                }
                                return addAnalyticsForGitOperation(
                                                AnalyticsEvents.GIT_IMPORT,
                                                newApplication,
                                                AppsmithError.GIT_APPLICATION_LIMIT_ERROR.getErrorType(),
                                                AppsmithError.GIT_APPLICATION_LIMIT_ERROR.getMessage(),
                                                true)
                                        .flatMap(user -> Mono.error(
                                                new AppsmithException(AppsmithError.GIT_APPLICATION_LIMIT_ERROR)));
                            });
                })
                .flatMap(tuple -> {
                    GitAuth gitAuth = tuple.getT1();
                    Application application = tuple.getT2();
                    Path repoSuffix = Paths.get(application.getWorkspaceId(), application.getId(), repoName);
                    Mono<Map<String, GitProfile>> profileMono =
                            updateOrCreateGitProfileForCurrentUser(gitConnectDTO.getGitProfile(), application.getId());

                    Mono<String> defaultBranchMono = gitExecutor
                            .cloneRemoteIntoArtifactRepo(
                                    repoSuffix,
                                    gitConnectDTO.getRemoteUrl(),
                                    gitAuth.getPrivateKey(),
                                    gitAuth.getPublicKey())
                            .onErrorResume(error -> {
                                log.error("Error while cloning the remote repo, {}", error.getMessage());
                                return addAnalyticsForGitOperation(
                                                AnalyticsEvents.GIT_IMPORT,
                                                application,
                                                error.getClass().getName(),
                                                error.getMessage(),
                                                false)
                                        .flatMap(user -> fileUtils
                                                .deleteLocalRepo(repoSuffix)
                                                .then(applicationPageService.deleteApplication(application.getId())))
                                        .flatMap(application1 -> {
                                            if (error instanceof TransportException) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                                            } else if (error instanceof InvalidRemoteException) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.INVALID_PARAMETER, "remote url"));
                                            } else if (error instanceof TimeoutException) {
                                                return Mono.error(
                                                        new AppsmithException(AppsmithError.GIT_EXECUTION_TIMEOUT));
                                            }
                                            return Mono.error(new AppsmithException(
                                                    AppsmithError.GIT_ACTION_FAILED, "clone", error));
                                        });
                            });

                    return defaultBranchMono.zipWith(isPrivateRepoMono).flatMap(tuple2 -> {
                        String defaultBranch = tuple2.getT1();
                        boolean isRepoPrivate = tuple2.getT2();
                        GitArtifactMetadata gitArtifactMetadata = new GitArtifactMetadata();
                        gitArtifactMetadata.setGitAuth(gitAuth);
                        gitArtifactMetadata.setDefaultApplicationId(application.getId());
                        gitArtifactMetadata.setBranchName(defaultBranch);
                        gitArtifactMetadata.setDefaultBranchName(defaultBranch);
                        gitArtifactMetadata.setRemoteUrl(gitConnectDTO.getRemoteUrl());
                        gitArtifactMetadata.setRepoName(repoName);
                        gitArtifactMetadata.setBrowserSupportedRemoteUrl(
                                GitUtils.convertSshUrlToBrowserSupportedUrl(gitConnectDTO.getRemoteUrl()));
                        gitArtifactMetadata.setIsRepoPrivate(isRepoPrivate);
                        gitArtifactMetadata.setLastCommittedAt(Instant.now());

                        application.setGitApplicationMetadata(gitArtifactMetadata);
                        return Mono.just(application).zipWith(profileMono);
                    });
                })
                .flatMap(objects -> {
                    Application application = objects.getT1();
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    String defaultBranch = gitArtifactMetadata.getDefaultBranchName();

                    Mono<List<Datasource>> datasourceMono = datasourceService
                            .getAllByWorkspaceIdWithStorages(workspaceId, datasourcePermission.getEditPermission())
                            .collectList();
                    Mono<List<Plugin>> pluginMono =
                            pluginService.getDefaultPlugins().collectList();
                    Mono<ApplicationJson> applicationJsonMono = fileUtils
                            .reconstructApplicationJsonFromGitRepoWithAnalytics(
                                    workspaceId, application.getId(), gitArtifactMetadata.getRepoName(), defaultBranch)
                            .onErrorResume(error -> {
                                log.error("Error while constructing application from git repo", error);
                                return deleteApplicationCreatedFromGitImport(
                                                application.getId(),
                                                application.getWorkspaceId(),
                                                gitArtifactMetadata.getRepoName())
                                        .flatMap(application1 -> Mono.error(new AppsmithException(
                                                AppsmithError.GIT_FILE_SYSTEM_ERROR, error.getMessage())));
                            });

                    return Mono.zip(applicationJsonMono, datasourceMono, pluginMono)
                            .flatMap(data -> {
                                ApplicationJson applicationJson = data.getT1();
                                List<Datasource> datasourceList = data.getT2();
                                List<Plugin> pluginList = data.getT3();

                                if (Optional.ofNullable(applicationJson.getExportedApplication())
                                                .isEmpty()
                                        || applicationJson.getPageList().isEmpty()) {
                                    return deleteApplicationCreatedFromGitImport(
                                                    application.getId(),
                                                    application.getWorkspaceId(),
                                                    gitArtifactMetadata.getRepoName())
                                            .then(Mono.error(new AppsmithException(
                                                    AppsmithError.GIT_ACTION_FAILED,
                                                    "import",
                                                    "Cannot import app from an empty repo")));
                                }

                                // If there is an existing datasource with the same name but a different type from that
                                // in the repo, the import api should fail
                                if (checkIsDatasourceNameConflict(
                                        datasourceList, applicationJson.getDatasourceList(), pluginList)) {
                                    return deleteApplicationCreatedFromGitImport(
                                                    application.getId(),
                                                    application.getWorkspaceId(),
                                                    gitArtifactMetadata.getRepoName())
                                            .then(Mono.error(new AppsmithException(
                                                    AppsmithError.GIT_ACTION_FAILED,
                                                    "import",
                                                    "Datasource already exists with the same name")));
                                }

                                applicationJson.getExportedApplication().setGitApplicationMetadata(gitArtifactMetadata);
                                return importService
                                        .importArtifactInWorkspaceFromGit(
                                                workspaceId, application.getId(), applicationJson, defaultBranch)
                                        .map(importableArtifact -> (Application) importableArtifact)
                                        .onErrorResume(throwable -> deleteApplicationCreatedFromGitImport(
                                                        application.getId(),
                                                        application.getWorkspaceId(),
                                                        gitArtifactMetadata.getRepoName())
                                                .flatMap(application1 -> Mono.error(new AppsmithException(
                                                        AppsmithError.GIT_FILE_SYSTEM_ERROR, throwable.getMessage()))));
                            });
                })
                .flatMap(application -> applicationPageService.publish(application.getId(), false))
                // Add un-configured datasource to the list to response
                .flatMap(application -> importService.getArtifactImportDTO(
                        application.getWorkspaceId(), application.getId(), application, APPLICATION))
                .map(importableArtifactDTO -> (ApplicationImportDTO) importableArtifactDTO)
                // Add analytics event
                .flatMap(applicationImportDTO -> {
                    Application application = applicationImportDTO.getApplication();
                    return addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_IMPORT,
                                    application,
                                    application.getGitApplicationMetadata().getIsRepoPrivate())
                            .thenReturn(applicationImportDTO);
                });

        return Mono.create(
                sink -> importedApplicationMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<GitAuth> generateSSHKey(String keyType) {
        GitAuth gitAuth = GitDeployKeyGenerator.generateSSHKey(keyType);

        GitDeployKeys gitDeployKeys = new GitDeployKeys();
        gitDeployKeys.setGitAuth(gitAuth);

        return sessionUserService
                .getCurrentUser()
                .flatMap(user -> {
                    gitDeployKeys.setEmail(user.getEmail());
                    return gitDeployKeysRepository
                            .findByEmail(user.getEmail())
                            .switchIfEmpty(gitDeployKeysRepository.save(gitDeployKeys))
                            .flatMap(gitDeployKeys1 -> {
                                if (gitDeployKeys.equals(gitDeployKeys1)) {
                                    return Mono.just(gitDeployKeys1);
                                }
                                // Overwrite the existing keys
                                gitDeployKeys1.setGitAuth(gitDeployKeys.getGitAuth());
                                return gitDeployKeysRepository.save(gitDeployKeys1);
                            });
                })
                .thenReturn(gitAuth);
    }

    @Override
    public Mono<Boolean> testConnection(String defaultApplicationId) {
        return getApplicationById(defaultApplicationId, applicationPermission.getEditPermission())
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    if (isInvalidDefaultApplicationGitMetadata(gitArtifactMetadata)) {
                        return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                    }
                    return gitExecutor
                            .testConnection(
                                    gitArtifactMetadata.getGitAuth().getPublicKey(),
                                    gitArtifactMetadata.getGitAuth().getPrivateKey(),
                                    gitArtifactMetadata.getRemoteUrl())
                            .zipWith(Mono.just(application))
                            .onErrorResume(error -> {
                                log.error(
                                        "Error while testing the connection to th remote repo "
                                                + gitArtifactMetadata.getRemoteUrl() + " ",
                                        error);
                                return addAnalyticsForGitOperation(
                                                AnalyticsEvents.GIT_TEST_CONNECTION,
                                                application,
                                                error.getClass().getName(),
                                                error.getMessage(),
                                                application
                                                        .getGitApplicationMetadata()
                                                        .getIsRepoPrivate())
                                        .flatMap(application1 -> {
                                            if (error instanceof TransportException) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.INVALID_GIT_SSH_CONFIGURATION));
                                            }
                                            if (error instanceof InvalidRemoteException) {
                                                return Mono.error(new AppsmithException(
                                                        AppsmithError.INVALID_GIT_CONFIGURATION, error.getMessage()));
                                            }
                                            if (error instanceof TimeoutException) {
                                                return Mono.error(
                                                        new AppsmithException(AppsmithError.GIT_EXECUTION_TIMEOUT));
                                            }
                                            return Mono.error(new AppsmithException(
                                                    AppsmithError.GIT_GENERIC_ERROR, error.getMessage()));
                                        });
                            });
                })
                .flatMap(objects -> {
                    Application application = objects.getT2();
                    return addAnalyticsForGitOperation(
                                    AnalyticsEvents.GIT_TEST_CONNECTION,
                                    application,
                                    application.getGitApplicationMetadata().getIsRepoPrivate())
                            .thenReturn(objects.getT1());
                });
    }

    @Override
    public Mono<Application> deleteBranch(String defaultApplicationId, String branchName) {
        Mono<Application> deleteBranchMono = getApplicationById(
                        defaultApplicationId, applicationPermission.getEditPermission())
                .zipWhen(application ->
                        gitPrivateRepoHelper.isBranchProtected(application.getGitApplicationMetadata(), branchName))
                .map(objects -> {
                    if (objects.getT2()) {
                        throw new AppsmithException(
                                AppsmithError.GIT_ACTION_FAILED,
                                "delete",
                                "Cannot delete protected branch " + branchName);
                    }
                    return objects.getT1();
                })
                .flatMap(application -> addFileLock(defaultApplicationId).map(status -> application))
                .flatMap(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    Path repoPath = Paths.get(
                            application.getWorkspaceId(), defaultApplicationId, gitArtifactMetadata.getRepoName());
                    if (branchName.equals(gitArtifactMetadata.getDefaultBranchName())) {
                        return Mono.error(new AppsmithException(
                                AppsmithError.GIT_ACTION_FAILED, "delete branch", " Cannot delete default branch"));
                    }
                    return gitExecutor
                            .deleteBranch(repoPath, branchName)
                            .onErrorResume(throwable -> {
                                log.error("Delete branch failed {}", throwable.getMessage());
                                if (throwable instanceof CannotDeleteCurrentBranchException) {
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED,
                                            "delete branch",
                                            "Cannot delete current checked out branch"));
                                }
                                return Mono.error(new AppsmithException(
                                        AppsmithError.GIT_ACTION_FAILED, "delete branch", throwable.getMessage()));
                            })
                            .flatMap(isBranchDeleted ->
                                    releaseFileLock(defaultApplicationId).map(status -> isBranchDeleted))
                            .flatMap(isBranchDeleted -> {
                                if (FALSE.equals(isBranchDeleted)) {
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED,
                                            " delete branch. Branch does not exists in the repo"));
                                }
                                return applicationService
                                        .findByBranchNameAndDefaultApplicationId(
                                                branchName,
                                                defaultApplicationId,
                                                applicationPermission.getEditPermission())
                                        .flatMap(application1 -> {
                                            if (application1
                                                    .getId()
                                                    .equals(application1
                                                            .getGitApplicationMetadata()
                                                            .getDefaultApplicationId())) {
                                                return Mono.just(application1);
                                            }
                                            return applicationPageService.deleteApplicationByResource(application1);
                                        })
                                        .onErrorResume(throwable -> {
                                            log.warn("Unable to find branch with name ", throwable);
                                            return addAnalyticsForGitOperation(
                                                            AnalyticsEvents.GIT_DELETE_BRANCH,
                                                            application,
                                                            throwable.getClass().getName(),
                                                            throwable.getMessage(),
                                                            gitArtifactMetadata.getIsRepoPrivate())
                                                    .flatMap(application1 -> Mono.just(application1));
                                        });
                            });
                })
                .flatMap(application -> addAnalyticsForGitOperation(
                        AnalyticsEvents.GIT_DELETE_BRANCH,
                        application,
                        application.getGitApplicationMetadata().getIsRepoPrivate()))
                .map(responseUtils::updateApplicationWithDefaultResources)
                .name(GitSpan.OPS_DELETE_BRANCH)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(sink -> deleteBranchMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    @Override
    public Mono<Application> discardChanges(String defaultApplicationId, String branchName) {

        if (StringUtils.isEmptyOrNull(defaultApplicationId)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_PARAMETER, FieldName.APPLICATION_ID));
        }
        Mono<Application> branchedApplicationMono = applicationService
                .findByBranchNameAndDefaultApplicationId(
                        branchName, defaultApplicationId, applicationPermission.getEditPermission())
                .cache();

        Mono<Application> discardChangeMono;

        // Rehydrate the application from local file system
        discardChangeMono = branchedApplicationMono
                // Add file lock before proceeding with the git operation
                .flatMap(application -> addFileLock(defaultApplicationId).thenReturn(application))
                .flatMap(branchedApplication -> {
                    GitArtifactMetadata gitData = branchedApplication.getGitApplicationMetadata();
                    if (gitData == null || StringUtils.isEmptyOrNull(gitData.getDefaultApplicationId())) {
                        return Mono.error(
                                new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
                    }
                    Path repoSuffix = Paths.get(
                            branchedApplication.getWorkspaceId(),
                            gitData.getDefaultApplicationId(),
                            gitData.getRepoName());
                    return gitExecutor
                            .rebaseBranch(repoSuffix, branchName)
                            .flatMap(rebaseStatus -> {
                                return fileUtils.reconstructApplicationJsonFromGitRepoWithAnalytics(
                                        branchedApplication.getWorkspaceId(),
                                        branchedApplication
                                                .getGitApplicationMetadata()
                                                .getDefaultApplicationId(),
                                        branchedApplication
                                                .getGitApplicationMetadata()
                                                .getRepoName(),
                                        branchName);
                            })
                            .onErrorResume(throwable -> {
                                log.error("Git Discard & Rebase failed {}", throwable.getMessage());
                                return Mono.error(
                                        new AppsmithException(
                                                AppsmithError.GIT_ACTION_FAILED,
                                                "discard changes",
                                                "Please create a new branch and resolve the conflicts on remote repository before proceeding ahead."));
                            })
                            .flatMap(applicationJson -> importService.importArtifactInWorkspaceFromGit(
                                    branchedApplication.getWorkspaceId(),
                                    branchedApplication.getId(),
                                    applicationJson,
                                    branchName))
                            // Update the last deployed status after the rebase
                            .flatMap(application -> publishAndOrGetApplication(application.getId(), true));
                })
                .flatMap(application -> releaseFileLock(defaultApplicationId)
                        .then(this.addAnalyticsForGitOperation(AnalyticsEvents.GIT_DISCARD_CHANGES, application, null)))
                .map(responseUtils::updateApplicationWithDefaultResources)
                .name(GitSpan.OPS_DISCARD_CHANGES)
                .tap(Micrometer.observation(observationRegistry));

        return Mono.create(
                sink -> discardChangeMono.subscribe(sink::success, sink::error, null, sink.currentContext()));
    }

    /**
     * In some scenarios:
     * connect: after loading the modal, keyTypes is not available, so a network call has to be made to ssh-keypair.
     * import: cannot make a ssh-keypair call because application Id doesn’t exist yet, so API fails.
     *
     * @return Git docs urls for all the scenarios, client will cache this data and use it
     */
    @Override
    public Mono<List<GitDocsDTO>> getGitDocUrls() {
        ErrorReferenceDocUrl[] docSet = ErrorReferenceDocUrl.values();
        List<GitDocsDTO> gitDocsDTOList = new ArrayList<>();
        for (ErrorReferenceDocUrl docUrl : docSet) {
            GitDocsDTO gitDocsDTO = new GitDocsDTO();
            gitDocsDTO.setDocKey(docUrl);
            gitDocsDTO.setDocUrl(docUrl.getDocUrl());
            gitDocsDTOList.add(gitDocsDTO);
        }
        return Mono.just(gitDocsDTOList);
    }

    private Mono<Application> deleteApplicationCreatedFromGitImport(
            String applicationId, String workspaceId, String repoName) {
        Path repoSuffix = Paths.get(workspaceId, applicationId, repoName);
        return fileUtils.deleteLocalRepo(repoSuffix).then(applicationPageService.deleteApplication(applicationId));
    }

    private Mono<GitAuth> getSSHKeyForCurrentUser() {
        return sessionUserService
                .getCurrentUser()
                .flatMap(user -> gitDeployKeysRepository.findByEmail(user.getEmail()))
                .map(GitDeployKeys::getGitAuth);
    }

    private boolean checkIsDatasourceNameConflict(
            List<Datasource> existingDatasources,
            List<DatasourceStorage> importedDatasources,
            List<Plugin> pluginList) {
        // If we have an existing datasource with the same name but a different type from that in the repo, the import
        // api should fail
        for (DatasourceStorage datasourceStorage : importedDatasources) {
            // Collect the datasource(existing in workspace) which has same as of imported datasource
            // As names are unique we will need filter first element to check if the plugin id is matched
            Datasource filteredDatasource = existingDatasources.stream()
                    .filter(datasource1 -> datasource1.getName().equals(datasourceStorage.getName()))
                    .findFirst()
                    .orElse(null);

            // Check if both of the datasource's are of the same plugin type
            if (filteredDatasource != null) {
                long matchCount = pluginList.stream()
                        .filter(plugin -> {
                            final String pluginReference =
                                    plugin.getPluginName() == null ? plugin.getPackageName() : plugin.getPluginName();

                            return plugin.getId().equals(filteredDatasource.getPluginId())
                                    && !datasourceStorage.getPluginId().equals(pluginReference);
                        })
                        .count();
                if (matchCount > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isInvalidDefaultApplicationGitMetadata(GitArtifactMetadata gitArtifactMetadata) {
        return Optional.ofNullable(gitArtifactMetadata).isEmpty()
                || Optional.ofNullable(gitArtifactMetadata.getGitAuth()).isEmpty()
                || StringUtils.isEmptyOrNull(gitArtifactMetadata.getGitAuth().getPrivateKey())
                || StringUtils.isEmptyOrNull(gitArtifactMetadata.getGitAuth().getPublicKey());
    }

    private Mono<String> commitAndPushWithDefaultCommit(
            Path repoSuffix, GitAuth auth, GitArtifactMetadata gitArtifactMetadata, GitDefaultCommitMessage reason) {
        return gitExecutor
                .commitArtifact(
                        repoSuffix,
                        DEFAULT_COMMIT_MESSAGE + reason.getReason(),
                        APPSMITH_BOT_USERNAME,
                        emailConfig.getSupportEmailAddress(),
                        true,
                        false)
                .onErrorResume(error -> {
                    if (error instanceof EmptyCommitException) {
                        return Mono.just(EMPTY_COMMIT_ERROR_MESSAGE);
                    }
                    return Mono.error(
                            new AppsmithException(AppsmithError.GIT_ACTION_FAILED, "commit", error.getMessage()));
                })
                .flatMap(commitMessage -> gitExecutor
                        .pushApplication(
                                repoSuffix,
                                gitArtifactMetadata.getRemoteUrl(),
                                auth.getPublicKey(),
                                auth.getPrivateKey(),
                                gitArtifactMetadata.getBranchName())
                        .flatMap(pushResult -> {
                            if (pushResult.contains("REJECTED")) {
                                return Mono.error(new AppsmithException(AppsmithError.GIT_UPSTREAM_CHANGES));
                            }
                            return Mono.just(pushResult);
                        }));
    }

    /**
     * Method to pull the files from remote repo and rehydrate the application
     *
     * @param defaultApplication application which acts as the root for the concerned branch
     * @param branchName         branch for which the pull is required
     * @return pull DTO with updated application
     */
    private Mono<GitPullDTO> pullAndRehydrateApplication(Application defaultApplication, String branchName) {

        /*
        1. Checkout to the concerned branch
        2. Do git pull after
            On Merge conflict - throw exception and ask user to resolve these conflicts on remote
            TODO create new branch and push the changes to remote and ask the user to resolve it on github/gitlab UI
        3. Rehydrate the application from filesystem so that the latest changes from remote are rendered to the application
        */
        GitArtifactMetadata gitData = defaultApplication.getGitApplicationMetadata();
        if (isInvalidDefaultApplicationGitMetadata(gitData)) {
            return Mono.error(new AppsmithException(AppsmithError.INVALID_GIT_CONFIGURATION, GIT_CONFIG_ERROR));
        }
        Path repoSuffix = Paths.get(
                defaultApplication.getWorkspaceId(), gitData.getDefaultApplicationId(), gitData.getRepoName());

        Mono<Application> branchedApplicationMono = applicationService.findByBranchNameAndDefaultApplicationId(
                branchName, defaultApplication.getId(), applicationPermission.getEditPermission());

        return branchedApplicationMono
                .flatMap(branchedApplication -> {
                    // git checkout and pull origin branchName
                    try {
                        Mono<MergeStatusDTO> pullStatusMono = gitExecutor
                                .checkoutToBranch(repoSuffix, branchName)
                                .then(gitExecutor.pullApplication(
                                        repoSuffix,
                                        gitData.getRemoteUrl(),
                                        branchName,
                                        gitData.getGitAuth().getPrivateKey(),
                                        gitData.getGitAuth().getPublicKey()))
                                .onErrorResume(error -> {
                                    if (error.getMessage().contains("conflict")) {
                                        return Mono.error(new AppsmithException(
                                                AppsmithError.GIT_PULL_CONFLICTS, error.getMessage()));
                                    } else if (error.getMessage().contains("Nothing to fetch")) {
                                        MergeStatusDTO mergeStatus = new MergeStatusDTO();
                                        mergeStatus.setStatus(
                                                "Nothing to fetch from remote. All changes are up to date.");
                                        mergeStatus.setMergeAble(true);
                                        return Mono.just(mergeStatus);
                                    }
                                    return Mono.error(new AppsmithException(
                                            AppsmithError.GIT_ACTION_FAILED, "pull", error.getMessage()));
                                })
                                .cache();
                        // Rehydrate the application from file system
                        Mono<ApplicationJson> applicationJsonMono = pullStatusMono.flatMap(
                                status -> fileUtils.reconstructApplicationJsonFromGitRepoWithAnalytics(
                                        branchedApplication.getWorkspaceId(),
                                        branchedApplication
                                                .getGitApplicationMetadata()
                                                .getDefaultApplicationId(),
                                        branchedApplication
                                                .getGitApplicationMetadata()
                                                .getRepoName(),
                                        branchName));

                        return Mono.zip(pullStatusMono, Mono.just(branchedApplication), applicationJsonMono);
                    } catch (IOException e) {
                        return Mono.error(new AppsmithException(AppsmithError.GIT_FILE_SYSTEM_ERROR, e.getMessage()));
                    }
                })
                .flatMap(tuple -> {
                    MergeStatusDTO status = tuple.getT1();
                    Application branchedApplication = tuple.getT2();
                    ApplicationJson applicationJson = tuple.getT3();

                    // Get the latest application with all the changes
                    // Commit and push changes to sync with remote
                    return importService
                            .importArtifactInWorkspaceFromGit(
                                    branchedApplication.getWorkspaceId(),
                                    branchedApplication.getId(),
                                    applicationJson,
                                    branchName)
                            .map(importableArtifact -> (Application) importableArtifact)
                            .flatMap(application -> addAnalyticsForGitOperation(
                                            AnalyticsEvents.GIT_PULL,
                                            application,
                                            application
                                                    .getGitApplicationMetadata()
                                                    .getIsRepoPrivate())
                                    .thenReturn(application))
                            .flatMap(application -> {
                                GitCommitDTO commitDTO = new GitCommitDTO();
                                commitDTO.setCommitMessage(DEFAULT_COMMIT_MESSAGE
                                        + GitDefaultCommitMessage.SYNC_WITH_REMOTE_AFTER_PULL.getReason());
                                commitDTO.setDoPush(true);

                                GitPullDTO gitPullDTO = new GitPullDTO();
                                gitPullDTO.setMergeStatus(status);
                                gitPullDTO.setApplication(
                                        responseUtils.updateApplicationWithDefaultResources(application));

                                // Make commit and push after pull is successful to have a clean repo
                                return this.commitApplication(
                                                commitDTO,
                                                application
                                                        .getGitApplicationMetadata()
                                                        .getDefaultApplicationId(),
                                                branchName)
                                        .thenReturn(gitPullDTO);
                            });
                });
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents eventName, Application application, Boolean isRepoPrivate) {
        return addAnalyticsForGitOperation(eventName, application, "", "", isRepoPrivate, false);
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents eventName, String branchName, Application application) {
        return addAnalyticsForGitOperation(eventName, application, null, null, null, false, null, branchName);
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents eventName,
            Application application,
            String errorType,
            String errorMessage,
            Boolean isRepoPrivate) {
        return addAnalyticsForGitOperation(eventName, application, errorType, errorMessage, isRepoPrivate, false);
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents event,
            Application application,
            String errorType,
            String errorMessage,
            Boolean isRepoPrivate,
            Boolean isSystemGenerated) {
        return addAnalyticsForGitOperation(
                event, application, errorType, errorMessage, isRepoPrivate, isSystemGenerated, null);
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents event,
            Application application,
            String errorType,
            String errorMessage,
            Boolean isRepoPrivate,
            Boolean isSystemGenerated,
            Boolean isMergeable) {

        String branchName = application.getGitApplicationMetadata() != null
                ? application.getGitApplicationMetadata().getBranchName()
                : null;
        return addAnalyticsForGitOperation(
                event, application, errorType, errorMessage, isRepoPrivate, isSystemGenerated, isMergeable, branchName);
    }

    private Mono<Application> addAnalyticsForGitOperation(
            AnalyticsEvents event,
            Application application,
            String errorType,
            String errorMessage,
            Boolean isRepoPrivate,
            Boolean isSystemGenerated,
            Boolean isMergeable,
            String branchName) {
        GitArtifactMetadata gitData = application.getGitApplicationMetadata();
        Map<String, Object> analyticsProps = new HashMap<>();
        if (gitData != null) {
            analyticsProps.put(FieldName.APPLICATION_ID, gitData.getDefaultApplicationId());
            analyticsProps.put("appId", gitData.getDefaultApplicationId());
            analyticsProps.put(FieldName.BRANCH_NAME, branchName);
            analyticsProps.put(FieldName.GIT_HOSTING_PROVIDER, GitUtils.getGitProviderName(gitData.getRemoteUrl()));
            analyticsProps.put(FieldName.REPO_URL, gitData.getRemoteUrl());
            if (event == AnalyticsEvents.GIT_COMMIT) {
                analyticsProps.put("isAutoCommit", false);
            }
        }
        // Do not include the error data points in the map for success states
        if (!StringUtils.isEmptyOrNull(errorMessage) || !StringUtils.isEmptyOrNull(errorType)) {
            analyticsProps.put("errorMessage", errorMessage);
            analyticsProps.put("errorType", errorType);
        }
        // Do not include the isMergeable for all the events
        if (isMergeable != null) {
            analyticsProps.put(FieldName.IS_MERGEABLE, isMergeable);
        }
        analyticsProps.putAll(Map.of(
                FieldName.ORGANIZATION_ID,
                defaultIfNull(application.getWorkspaceId(), ""),
                "orgId",
                defaultIfNull(application.getWorkspaceId(), ""),
                "branchApplicationId",
                defaultIfNull(application.getId(), ""),
                "isRepoPrivate",
                defaultIfNull(isRepoPrivate, ""),
                "isSystemGenerated",
                defaultIfNull(isSystemGenerated, "")));
        final Map<String, Object> eventData =
                Map.of(FieldName.APP_MODE, ApplicationMode.EDIT.toString(), FieldName.APPLICATION, application);
        analyticsProps.put(FieldName.EVENT_DATA, eventData);
        return sessionUserService.getCurrentUser().flatMap(user -> analyticsService
                .sendEvent(event.getEventName(), user.getUsername(), analyticsProps)
                .thenReturn(application));
    }

    private Mono<Boolean> addFileLock(String defaultApplicationId) {
        return redisUtils
                .addFileLock(defaultApplicationId)
                .retryWhen(Retry.fixedDelay(MAX_RETRIES, RETRY_DELAY)
                        .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                            throw new AppsmithException(AppsmithError.GIT_FILE_IN_USE);
                        }))
                .name(GitSpan.ADD_FILE_LOCK)
                .tap(Micrometer.observation(observationRegistry));
    }

    private Mono<Boolean> releaseFileLock(String defaultApplicationId) {
        return redisUtils
                .releaseFileLock(defaultApplicationId)
                .name(GitSpan.RELEASE_FILE_LOCK)
                .tap(Micrometer.observation(observationRegistry));
    }

    @Override
    public Mono<List<String>> updateProtectedBranches(String defaultApplicationId, List<String> branchNames) {
        return getApplicationById(defaultApplicationId, applicationPermission.getManageProtectedBranchPermission())
                .flatMap(rootApplication -> {
                    GitArtifactMetadata metadata = rootApplication.getGitApplicationMetadata();
                    String defaultBranchName = metadata.getDefaultBranchName();

                    if (branchNames.isEmpty()
                            || (branchNames.size() == 1 && branchNames.get(0).equals(defaultBranchName))) {
                        // keep a copy of old protected branches as it's required to send analytics event later
                        List<String> oldProtectedBranches = metadata.getBranchProtectionRules() != null
                                ? metadata.getBranchProtectionRules()
                                : List.of();

                        // user wants to unprotect all branches or user wants to protect only default branch
                        metadata.setBranchProtectionRules(branchNames);
                        return applicationService
                                .save(rootApplication)
                                .then(applicationService.updateProtectedBranches(defaultApplicationId, branchNames))
                                .then(sendBranchProtectionAnalytics(rootApplication, oldProtectedBranches, branchNames))
                                .thenReturn(branchNames);
                    } else {
                        // user want to protect multiple branches, not allowed
                        return Mono.error(new AppsmithException(AppsmithError.UNSUPPORTED_OPERATION));
                    }
                })
                .as(transactionalOperator::transactional);
    }

    @Override
    public Mono<List<String>> getProtectedBranches(String defaultApplicationId) {
        return getApplicationById(defaultApplicationId, applicationPermission.getEditPermission())
                .map(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    /*
                     user may have multiple branches as protected, but we only return the default branch
                     as protected branch if it's present in the list of protected branches
                    */
                    List<String> protectedBranches = gitArtifactMetadata.getBranchProtectionRules();
                    String defaultBranchName = gitArtifactMetadata.getDefaultBranchName();

                    if (!CollectionUtils.isNullOrEmpty(protectedBranches)
                            && protectedBranches.contains(defaultBranchName)) {
                        return List.of(defaultBranchName);
                    } else {
                        return List.of();
                    }
                });
    }

    @Override
    public Mono<Boolean> autoCommitApplication(String defaultApplicationId, String branchName) {
        return gitAutoCommitHelper.autoCommitApplication(defaultApplicationId, branchName);
    }

    @Override
    public Mono<AutoCommitProgressDTO> getAutoCommitProgress(String applicationId) {
        return gitAutoCommitHelper.getAutoCommitProgress(applicationId);
    }

    @Override
    public Mono<Boolean> toggleAutoCommitEnabled(String defaultApplicationId) {
        return getApplicationById(defaultApplicationId, applicationPermission.getManageAutoCommitPermission())
                .map(application -> {
                    GitArtifactMetadata gitArtifactMetadata = application.getGitApplicationMetadata();
                    if (!application.getId().equals(gitArtifactMetadata.getDefaultApplicationId())) {
                        log.error(
                                "failed tp toggle auto commit. reason: {} is not the root application id",
                                defaultApplicationId);
                        throw new AppsmithException(AppsmithError.INVALID_PARAMETER, "default application id");
                    }

                    AutoCommitConfig autoCommitConfig = gitArtifactMetadata.getAutoCommitConfig();
                    if (autoCommitConfig.getEnabled()) {
                        autoCommitConfig.setEnabled(FALSE);
                    } else {
                        autoCommitConfig.setEnabled(TRUE);
                    }
                    // need to call the setter because getter returns a default config if attribute is null
                    application.getGitApplicationMetadata().setAutoCommitConfig(autoCommitConfig);
                    return application;
                })
                .flatMap(application -> applicationService
                        .save(application)
                        .thenReturn(application
                                .getGitApplicationMetadata()
                                .getAutoCommitConfig()
                                .getEnabled()));
    }

    /**
     * Sends one or more analytics events when there's a change in protected branches.
     * If n number of branches are un-protected and m number of branches are protected, it'll send m+n number of
     * events. It receives the list of branches before and after the action.
     * For example, if user has "main" and "develop" branches as protected and wants to include "staging" branch as
     * protected as well, then oldProtectedBranches will be ["main", "develop"] and newProtectedBranches will be
     * ["main", "develop", "staging"]
     *
     * @param application          Application object of the root application
     * @param oldProtectedBranches List of branches that were protected before this action.
     * @param newProtectedBranches List of branches that are going to be protected.
     * @return An empty Mono
     */
    protected Mono<Void> sendBranchProtectionAnalytics(
            Application application, List<String> oldProtectedBranches, List<String> newProtectedBranches) {
        List<String> itemsAdded = new ArrayList<>(newProtectedBranches); // add all new items
        itemsAdded.removeAll(oldProtectedBranches); // remove the items that were present earlier

        List<String> itemsRemoved = new ArrayList<>(oldProtectedBranches); // add all old items
        itemsRemoved.removeAll(newProtectedBranches); // remove the items that are also present in new list

        List<Mono<Application>> eventSenderMonos = new ArrayList<>();

        // send an analytics event for each removed branch
        for (String branchName : itemsRemoved) {
            eventSenderMonos.add(addAnalyticsForGitOperation(GIT_REMOVE_PROTECTED_BRANCH, branchName, application));
        }

        // send an analytics event for each newly protected branch
        for (String branchName : itemsAdded) {
            eventSenderMonos.add(addAnalyticsForGitOperation(GIT_ADD_PROTECTED_BRANCH, branchName, application));
        }

        return Flux.merge(eventSenderMonos).then();
    }
}
