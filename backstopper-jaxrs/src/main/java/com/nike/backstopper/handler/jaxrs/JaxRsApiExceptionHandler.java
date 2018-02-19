package com.nike.backstopper.handler.jaxrs;

import com.nike.backstopper.apierror.ApiError;
import com.nike.backstopper.apierror.projectspecificinfo.ProjectApiErrors;
import com.nike.backstopper.handler.ApiExceptionHandlerServletApiBase;
import com.nike.backstopper.handler.ApiExceptionHandlerUtils;
import com.nike.backstopper.handler.ErrorResponseInfo;
import com.nike.backstopper.handler.RequestInfoForLogging;
import com.nike.backstopper.handler.UnexpectedMajorExceptionHandlingError;
import com.nike.backstopper.handler.jaxrs.config.JaxRsApiExceptionHandlerListenerList;
import com.nike.backstopper.handler.listener.ApiExceptionHandlerListener;
import com.nike.backstopper.model.DefaultErrorContractDTO;
import com.nike.backstopper.model.util.JsonUtilWithDefaultErrorContractDTOSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;

/**
 * An {@link ApiExceptionHandlerServletApiBase} extension that hooks into JAX-RS via its
 * {@link ExceptionMapper} implementation, specifically {@link ExceptionMapper#toResponse(Throwable)}.
 *
 * <p>Any errors not handled here are things we don't know how to deal with and will fall through to {@link
 * JaxRsUnhandledExceptionHandler}.
 *
 * <p>It is expected and recommended that this handler (or an extension of it) is the sole {@link ExceptionMapper} for
 * the application.
 *
 * @author Michael Irwin
 */
@Provider
@Singleton
public class JaxRsApiExceptionHandler extends ApiExceptionHandlerServletApiBase<Response.ResponseBuilder>
    implements ExceptionMapper<Throwable> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    /**
     * The {@link JaxRsUnhandledExceptionHandler} that handles any
     * exceptions we weren't anticipating
     */
    @SuppressWarnings("WeakerAccess")
    protected final JaxRsUnhandledExceptionHandler jerseyUnhandledExceptionHandler;

    @Context
    protected HttpServletRequest request;

    @Context
    protected HttpServletResponse response;

    @Inject
    public JaxRsApiExceptionHandler(ProjectApiErrors projectApiErrors,
                                    JaxRsApiExceptionHandlerListenerList apiExceptionHandlerListeners,
                                    ApiExceptionHandlerUtils apiExceptionHandlerUtils,
                                    JaxRsUnhandledExceptionHandler jerseyUnhandledExceptionHandler) {

        this(projectApiErrors, apiExceptionHandlerListeners.listeners, apiExceptionHandlerUtils, jerseyUnhandledExceptionHandler);
    }

    public JaxRsApiExceptionHandler(ProjectApiErrors projectApiErrors,
                                    List<ApiExceptionHandlerListener> apiExceptionHandlerListeners,
                                    ApiExceptionHandlerUtils apiExceptionHandlerUtils,
                                    JaxRsUnhandledExceptionHandler jerseyUnhandledExceptionHandler) {

        super(projectApiErrors, apiExceptionHandlerListeners, apiExceptionHandlerUtils);

       if (jerseyUnhandledExceptionHandler == null)
         throw new IllegalArgumentException("jerseyUnhandledExceptionHandler cannot be null");

       this.jerseyUnhandledExceptionHandler = jerseyUnhandledExceptionHandler;
     }

    @Override
    public Response.ResponseBuilder prepareFrameworkRepresentation(
        DefaultErrorContractDTO errorContractDTO, int httpStatusCode, Collection<ApiError> rawFilteredApiErrors,
        Throwable originalException, RequestInfoForLogging request
    ) {
        return Response.status(httpStatusCode).entity(
            JsonUtilWithDefaultErrorContractDTOSupport.writeValueAsString(errorContractDTO));
    }

    @Override
    public Response toResponse(Throwable e) {

        ErrorResponseInfo<Response.ResponseBuilder> exceptionHandled;

        try {
            exceptionHandled = maybeHandleException(e, request, response);

            if (exceptionHandled == null) {
                if (logger.isDebugEnabled()) {
                    logger.debug("No suitable handlers found for exception=" + e.getMessage() + ". "
                                 + JaxRsUnhandledExceptionHandler.class.getName() + " should handle it.");
                }
                exceptionHandled = jerseyUnhandledExceptionHandler.handleException(e, request, response);
            }

        }
        catch (UnexpectedMajorExceptionHandlingError ohNoException) {
            logger.error("Unexpected major error while handling exception. " +
                         JaxRsUnhandledExceptionHandler.class.getName() + " should handle it.", ohNoException);
            exceptionHandled = jerseyUnhandledExceptionHandler.handleException(e, request, response);
        }

        Response.ResponseBuilder responseBuilder = setContentType(exceptionHandled, request, response, e);

        // NOTE: We don't have to add headers to the response here - it's already been done in the
        //      ApiExceptionHandlerServletApiBase.processServletResponse(...) method.

        return responseBuilder.build();
    }

    /**
     * Sets the {@code Content-Type} header on the given {@link ErrorResponseInfo#frameworkRepresentationObj}
     * (which is a {@link javax.ws.rs.core.Response.ResponseBuilder}) and then returns it. Defaults to {@link
     * MediaType#APPLICATION_JSON}. If you need a different content type for your response you can override this method
     * to do something else.
     *
     * @param errorResponseInfo The {@link ErrorResponseInfo} that was generated for this request. The expectation is
     * that any override of this method will {@code return errorResponseInfo.frameworkRepresentationObj.header(...)}
     * to set the {@code Content-Type} header for the response.
     * @param request The {@link HttpServletRequest} for this request - useful if you need it to help determine the
     * {@code Content-Type} for the response.
     * @param response The {@link HttpServletResponse} for this request - useful if you need it to help determine the
     * {@code Content-Type} for the response.
     * @param ex The exception that backstopper is handling - useful if you need it to help determine the
     * {@code Content-Type} for the response.
     * @return The given {@link ErrorResponseInfo}'s {@link ErrorResponseInfo#frameworkRepresentationObj} after setting
     * the {@code Content-Type} header on it - defaults to {@link MediaType#APPLICATION_JSON}. Override this method
     * if you need to send a different {@code Content-Type}.
     */
    protected Response.ResponseBuilder setContentType(
        ErrorResponseInfo<Response.ResponseBuilder> errorResponseInfo, HttpServletRequest request,
        HttpServletResponse response, Throwable ex
    ) {
        return errorResponseInfo.frameworkRepresentationObj.header("Content-Type", MediaType.APPLICATION_JSON);
    }
}
