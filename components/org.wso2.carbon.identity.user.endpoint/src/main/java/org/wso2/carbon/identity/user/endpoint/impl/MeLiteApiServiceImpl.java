package org.wso2.carbon.identity.user.endpoint.impl;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.identity.application.common.model.User;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.governance.service.notification.NotificationChannels;
import org.wso2.carbon.identity.recovery.IdentityRecoveryClientException;
import org.wso2.carbon.identity.recovery.IdentityRecoveryConstants;
import org.wso2.carbon.identity.recovery.IdentityRecoveryException;
import org.wso2.carbon.identity.recovery.bean.NotificationResponseBean;
import org.wso2.carbon.identity.recovery.signup.UserSelfRegistrationManager;
import org.wso2.carbon.identity.user.endpoint.*;
import org.wso2.carbon.identity.user.endpoint.dto.*;


import org.wso2.carbon.identity.user.endpoint.dto.SuccessfulUserCreationDTO;
import org.wso2.carbon.identity.user.endpoint.dto.ErrorDTO;
import org.wso2.carbon.identity.user.endpoint.dto.SelfLiteUserRegistrationRequestDTO;

import java.util.List;

import java.io.InputStream;
import org.apache.cxf.jaxrs.ext.multipart.Attachment;
import org.wso2.carbon.identity.user.endpoint.util.Utils;
import org.wso2.carbon.user.core.util.UserCoreUtil;

import javax.ws.rs.core.Response;

public class MeLiteApiServiceImpl extends MeLiteApiService {
    private static final Log LOG = LogFactory.getLog(MeLiteApiServiceImpl.class);

    // Default value for enabling API response.
    private static final boolean ENABLE_DETAILED_API_RESPONSE = false;

    @Override
    public Response meLitePost(SelfLiteUserRegistrationRequestDTO selfLiteUserRegistrationRequestDTO){

        //reject if email as username is not enabled.
        if(!UserCoreUtil.getIsEmailUserName()) {
            Utils.handleBadRequest(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_UNSUPPORTED_SELF_REGISTER_LITE_REQUEST.getMessage(),
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_UNSUPPORTED_SELF_REGISTER_LITE_REQUEST.getCode());
        }

        //reject if username is not present.
        if(selfLiteUserRegistrationRequestDTO == null || StringUtils.isBlank(selfLiteUserRegistrationRequestDTO.getEmail())){
            Utils.handleBadRequest(IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_BAD_SELF_REGISTER_REQUEST.getMessage(),
                    IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_BAD_SELF_REGISTER_REQUEST.getCode());
        }

        String tenantFromContext = (String) IdentityUtil.threadLocalProperties.get().get(Constants.TENANT_NAME_FROM_CONTEXT);
        User user = new User();
        user.setTenantDomain(MultitenantConstants.SUPER_TENANT_DOMAIN_NAME);
        user.setUserStoreDomain(IdentityUtil.getPrimaryDomainName());
        user.setUserName(selfLiteUserRegistrationRequestDTO.getEmail());

        if (StringUtils.isNotBlank(selfLiteUserRegistrationRequestDTO.getRealm())) {
            user.setUserStoreDomain(selfLiteUserRegistrationRequestDTO.getRealm());
        }

        if (StringUtils.isNotBlank(tenantFromContext)) {
            user.setTenantDomain(tenantFromContext);
        }

        UserSelfRegistrationManager userSelfRegistrationManager = Utils.getUserSelfRegistrationManager();
        NotificationResponseBean notificationResponseBean = null;
        try {
            notificationResponseBean = userSelfRegistrationManager
                    .registerUser(user, Utils.getProperties(selfLiteUserRegistrationRequestDTO.getProperties()));
        } catch (IdentityRecoveryClientException e) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Client Error while self registering lite user ", e);
            }
            if (IdentityRecoveryConstants.ErrorMessages.ERROR_CODE_USER_ALREADY_EXISTS.getCode().equals(e.getErrorCode())) {
                Utils.handleConflict(e.getMessage(), e.getErrorCode());
            } else {
                Utils.handleBadRequest(e.getMessage(), e.getErrorCode());
            }
        } catch (IdentityRecoveryException e) {
            Utils.handleInternalServerError(Constants.SERVER_ERROR, e.getErrorCode(), LOG, e);
        } catch (Throwable throwable) {
            Utils.handleInternalServerError(Constants.SERVER_ERROR, IdentityRecoveryConstants
                    .ErrorMessages.ERROR_CODE_UNEXPECTED.getCode(), LOG, throwable);
        }
        return buildSuccessfulAPIResponse(notificationResponseBean);
    }

    /**
     * Build response for a successful user self registration.
     *
     * @param notificationResponseBean NotificationResponseBean {@link NotificationResponseBean}
     * @return Response
     */
    private Response buildSuccessfulAPIResponse(NotificationResponseBean notificationResponseBean) {

        // Check whether detailed api responses are enabled.
        if (isDetailedResponseBodyEnabled()) {
            String notificationChannel = notificationResponseBean.getNotificationChannel();
            if (NotificationChannels.EXTERNAL_CHANNEL.getChannelType().equals(notificationChannel)) {
                // Handle response when the notifications are externally managed.
                SuccessfulUserCreationExternalResponseDTO successfulUserCreationDTO =
                        buildSuccessResponseForExternalChannel(notificationResponseBean);
                return Response.status(Response.Status.CREATED).entity(successfulUserCreationDTO).build();
            }
            SuccessfulUserCreationDTO successfulUserCreationDTO =
                    buildSuccessResponseForInternalChannels(notificationResponseBean);
            return Response.status(Response.Status.CREATED).entity(successfulUserCreationDTO).build();
        } else {
            if (notificationResponseBean != null) {
                String notificationChannel = notificationResponseBean.getNotificationChannel();
                /*If the notifications are required in the form of legacy response, and notifications are externally
                 managed, the recoveryId should be in the response as text*/
                if (NotificationChannels.EXTERNAL_CHANNEL.getChannelType().equals(notificationChannel)) {
                    return Response.status(Response.Status.CREATED).entity(notificationResponseBean.getRecoveryId())
                            .build();
                }
            }
            return Response.status(Response.Status.CREATED).build();
        }
    }

    /**
     * Build the successResponseDTO for successful user identification and channel retrieve when the notifications
     * are managed externally.
     *
     * @param notificationResponseBean NotificationResponseBean
     * @return SuccessfulUserCreationExternalResponseDTO
     */
    private SuccessfulUserCreationExternalResponseDTO buildSuccessResponseForExternalChannel(
            NotificationResponseBean notificationResponseBean) {

        SuccessfulUserCreationExternalResponseDTO successDTO = new SuccessfulUserCreationExternalResponseDTO();
        successDTO.setCode(notificationResponseBean.getCode());
        successDTO.setMessage(notificationResponseBean.getMessage());
        successDTO.setNotificationChannel(notificationResponseBean.getNotificationChannel());
        successDTO.setConfirmationCode(notificationResponseBean.getRecoveryId());
        return successDTO;
    }

    /**
     * Reads configurations from the identity.xml and return whether the detailed response is enabled or not.
     *
     * @return True if the legacy response is enabled.
     */
    private boolean isDetailedResponseBodyEnabled() {

        String enableDetailedResponseConfig = IdentityUtil
                .getProperty(Constants.ENABLE_DETAILED_API_RESPONSE);
        if (StringUtils.isEmpty(enableDetailedResponseConfig)) {
            // Return false if the user has not enabled the detailed response body.
            return ENABLE_DETAILED_API_RESPONSE;
        } else {
            return Boolean.parseBoolean(enableDetailedResponseConfig);
        }
    }

    /**
     * Build the successResponseDTO for successful user identification and channel retrieve when the notifications
     * are managed internally.
     *
     * @param notificationResponseBean NotificationResponseBean
     * @return SuccessfulUserCreationDTO
     */
    private SuccessfulUserCreationDTO buildSuccessResponseForInternalChannels(
            NotificationResponseBean notificationResponseBean) {

        SuccessfulUserCreationDTO successDTO = new SuccessfulUserCreationDTO();
        successDTO.setCode(notificationResponseBean.getCode());
        successDTO.setMessage(notificationResponseBean.getMessage());
        successDTO.setNotificationChannel(notificationResponseBean.getNotificationChannel());
        return successDTO;
    }
}