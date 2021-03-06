package io.higgs.http.server.protocol;

import io.higgs.core.FixedSortedList;
import io.higgs.core.InvokableMethod;
import io.higgs.core.MessageHandler;
import io.higgs.core.reflect.dependency.DependencyProvider;
import io.higgs.core.reflect.dependency.Injector;
import io.higgs.http.server.HttpRequest;
import io.higgs.http.server.HttpResponse;
import io.higgs.http.server.HttpStatus;
import io.higgs.http.server.MessagePusher;
import io.higgs.http.server.ParamInjector;
import io.higgs.http.server.StaticFileMethod;
import io.higgs.http.server.WebApplicationException;
import io.higgs.http.server.WrappedResponse;
import io.higgs.http.server.config.HttpConfig;
import io.higgs.http.server.params.HttpFile;
import io.higgs.http.server.transformers.ResponseTransformer;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;

import static io.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaders.getHeader;
import static io.netty.handler.codec.http.HttpHeaders.setContentLength;

/**
 * A stateful {@link MessageHandler} which processes HttpRequests.
 * There will be 1 instance of this class per Http request.
 *
 * @author Courtney Robinson <courtney@crlog.info>
 */
public class HttpHandler extends MessageHandler<HttpConfig, Object> {
    protected static final Class<HttpMethod> methodClass = HttpMethod.class;
    protected static HttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE); //Disk
    /**
     * The current HTTP request
     */
    protected HttpRequest request;
    protected HttpResponse res;
    /**
     * The current HTTP method which matches the current {@link #request}.
     * If no method matches this will be null
     */
    protected HttpMethod method;
    protected ParamInjector injector;
    protected HttpProtocolConfiguration protocolConfig;
    protected HttpPostRequestDecoder decoder;
    private Logger requestLogger = LoggerFactory.getLogger("request_logger");
    private boolean replied;

    public HttpHandler(HttpProtocolConfiguration config) {
        super(config.getServer().<HttpConfig>getConfig());
        HttpConfig c = config.getServer().getConfig();
        protocolConfig = config;
        injector = config.getInjector();
        // should delete file
        DiskFileUpload.deleteOnExitTemporaryFile = c.files.delete_temp_on_exit;
        // system temp directory
        DiskFileUpload.baseDirectory = c.files.temp_directory;
        // should delete file on
        DiskAttribute.deleteOnExitTemporaryFile = c.files.delete_temp_on_exit;
        // exit (in normal exit)
        DiskAttribute.baseDirectory = c.files.temp_directory; // system temp directory
    }

    public <M extends InvokableMethod> M findMethod(String path, ChannelHandlerContext ctx,
                                                    Object msg, Class<M> methodClass) {
        M m = super.findMethod(path, ctx, msg, methodClass);
        if (m == null && config.add_static_resource_filter) {
            StaticFileMethod fileMethod = new StaticFileMethod(protocolConfig.getServer().getFactories(),
                    protocolConfig);
            if (fileMethod.matches(path, ctx, msg)) {
                return (M) fileMethod;
            }
        }
        return m;
    }

    public void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent && !(msg instanceof FullHttpRequest) && replied) {
            return;  //can happen if exception was thrown before last http content received
        }
        replied = false;
        if (msg instanceof HttpRequest || msg instanceof FullHttpRequest) {
            if (msg instanceof HttpRequest) {
                request = (HttpRequest) msg;
            } else {
                request = new HttpRequest((FullHttpRequest) msg);
            }
            res = new HttpResponse(ctx.alloc().buffer());
            //apply transcriptions
            protocolConfig.getTranscriber().transcribe(request);
            //must always set protocol config before anything uses the request
            request.setConfig(protocolConfig);
            //initialise request, setting cookies, media types etc
            request.init(ctx);
            method = findMethod(request.getUri(), ctx, request, methodClass);
            if (method == null) {
                //404
                throw new WebApplicationException(HttpStatus.NOT_FOUND, request);
            }
            //set the path that matched
            request.setPath(method.path());
        }
        if (request == null || method == null) {
            log.warn(String.format("Method or request is null \n method \n%s \n request \n%s",
                    method, request));
            throw new WebApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, request);
        }
        //we have a request and it matches a registered method
        if (!io.netty.handler.codec.http.HttpMethod.POST.name().equalsIgnoreCase(request.getMethod().name()) &&
                !io.netty.handler.codec.http.HttpMethod.PUT.name().equalsIgnoreCase(request.getMethod().name())) {
            if (msg instanceof LastHttpContent) {
                //only post and put requests  are allowed to send form data so everything else just returns
                invoke(ctx);
            }
        } else {
            //if its a post or put request and a decoder doesn't exist then create one.
            if (decoder == null) {
                try {
                    decoder = new HttpPostRequestDecoder(factory, request);
                } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
                    log.warn("Unable to decode data", e1);
                    throw new WebApplicationException(HttpStatus.BAD_REQUEST, request);
                } catch (HttpPostRequestDecoder.IncompatibleDataDecoderException e) {
                    log.warn("Incompatible request type", e);
                    throw new WebApplicationException(HttpStatus.BAD_REQUEST, request);
                }
            }
            //decoder is created if it doesn't exist, can decode all if entire message received
            request.setChunked(HttpHeaders.isTransferEncodingChunked(request));
            request.setMultipart(decoder.isMultipart());

            if (msg instanceof HttpContent) {
                // New chunk is received
                HttpContent chunk = (HttpContent) msg;
                try {
                    decoder.offer(chunk);
                } catch (HttpPostRequestDecoder.ErrorDataDecoderException e1) {
                    log.warn("Unable to decode HTTP chunk", e1);
                    throw new WebApplicationException(HttpStatus.BAD_REQUEST, request);
                }
                readHttpDataChunkByChunk();
                if (chunk instanceof LastHttpContent) {
                    allHttpDataReceived(ctx);
                }
            }
        }
    }

    private void readHttpDataChunkByChunk() {
        try {
            while (decoder.hasNext()) {
                InterfaceHttpData data = decoder.next();
                if (data != null) {
                    // new value
                    writeHttpData(data);
                }
            }
        } catch (HttpPostRequestDecoder.EndOfDataDecoderException e1) {
            // end
        }
    }

    private void writeHttpData(InterfaceHttpData data) {
        //check if is file upload or attribute, attributes go into form params and file uploads to file params
        if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
            io.netty.handler.codec.http.multipart.Attribute field =
                    (io.netty.handler.codec.http.multipart.Attribute) data;
            try {
                //add form param
                String name = field.getName();
                int idx = name.indexOf("[");
                if (idx != -1 && name.endsWith("]")) {
                    String realName = name.substring(0, idx);
                    String fieldName = name.substring(idx + 1).replace(']', ' ').trim();
                    if (request.getFormParam().get(realName) == null) {
                        request.getFormParam().put(realName, new HashMap<String, String>());
                    }
                    ((HashMap<String, String>) request.getFormParam().get(realName)).put(fieldName, field.getValue());
                } else {
                    request.addFormField(name, field.getValue());
                }
            } catch (IOException e) {
                log.warn(String.format("unable to extract form field's value, field name = %s", field.getName()));
            }
        } else {
            if (data instanceof FileUpload) {
                //add form file
                request.addFormFile(new HttpFile((FileUpload) data));
            } else {
                if (data != null) {
                    log.warn(String.format("Unknown form type encountered Class: %s,data type:%s,name:%s",
                            data.getClass().getName(), data.getHttpDataType().name(), data.getName()));
                }
            }
        }
    }

    private void allHttpDataReceived(ChannelHandlerContext ctx) {
        //entire message/request received
        List<InterfaceHttpData> data;
        try {
            data = decoder.getBodyHttpDatas();
        } catch (HttpPostRequestDecoder.NotEnoughDataDecoderException e1) {
            log.warn("Not enough data to decode", e1);
            throw new WebApplicationException(HttpStatus.BAD_REQUEST, request);
        }
        //called when all data is received, go over request data and separate form fields from files
        for (InterfaceHttpData httpData : data) {
            writeHttpData(httpData);
        }
        invoke(ctx);
    }

    protected void invoke(final ChannelHandlerContext ctx) {
        MessagePusher pusher = new MessagePusher() {
            @Override
            public ChannelFuture push(Object message) {
                //http methods can return null or void and still have the response injected and modified
                //so null messages are allowed here
                Object wrappedRes = message != null && message instanceof WrappedResponse ?
                        ((WrappedResponse) message).data() : null;
                if (wrappedRes != null) {
                    message = wrappedRes;
                }

                Queue<ResponseTransformer> transformers = protocolConfig.getTransformers();
                return writeResponse(ctx, message, transformers);
            }

            @Override
            public ChannelHandlerContext ctx() {
                return ctx;
            }
        };
        //inject globally available dependencies
        Object[] params = Injector.inject(method.method().getParameterTypes(), new Object[0],
                DependencyProvider.from(pusher));
        //inject request specific dependencies
        injector.injectParams(method, request, res, ctx, params);
        try {
            Object response = method.invoke(ctx, request.getUri(), method, params);
            pusher.push(response);
        } catch (Throwable t) {
            if (t.getCause() instanceof WebApplicationException) {
                throw (WebApplicationException) t.getCause();
            } else {
                logDetailedFailMessage(true, params, t, method.method());
                throw new WebApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, request, t);
            }
        }
    }

    protected ChannelFuture writeResponse(ChannelHandlerContext ctx, Object response, Queue<ResponseTransformer> t) {
        if (res.isRedirect()) {
            return doWrite(ctx);
        }

        if (response instanceof HttpResponse) {
            res = (HttpResponse) response;
            return doWrite(ctx);
        }
        List<ResponseTransformer> ts = new FixedSortedList<>(t);
        boolean notAcceptable = false;
        for (ResponseTransformer transformer : ts) {
            if (transformer.canTransform(response, request, request.getMatchedMediaType(), method, ctx)) {
                transformer.transform(response, request, res, request.getMatchedMediaType(),
                        method, ctx);
                notAcceptable = false;
                break;
            }
            notAcceptable = true;
        }
        if (notAcceptable) {
            res.setStatus(HttpStatus.NOT_ACCEPTABLE);
        }
        return doWrite(ctx);
    }

    protected ChannelFuture doWrite(ChannelHandlerContext ctx) {
        //apply request cookies to response, this includes the session id
        res.finalizeCustomHeaders(request);
        if (config.log_requests) {
            SocketAddress address = ctx.channel().remoteAddress();
            //going with the Apache format
            //194.116.215.20 - [14/Nov/2005:22:28:57 +0000] “GET / HTTP/1.0″ 200 16440
            requestLogger.info(String.format("%s - [%s] \"%s %s %s\" %s %s",
                    address,
                    HttpHeaders.getDate(request, request.getCreatedAt().toDate()),
                    request.getMethod().name(),
                    request.getUri(),
                    request.getProtocolVersion(),
                    res.getStatus().code(),
                    getHeader(res, HttpHeaders.Names.CONTENT_LENGTH) == null ?
                            res.content().writerIndex() : HttpHeaders.getContentLength(res)
            ));
        }
        // Decide whether to close the connection or not.
        boolean close = HttpHeaders.Values.CLOSE.equalsIgnoreCase(request.headers().get(CONNECTION))
                || request.getProtocolVersion().equals(HttpVersion.HTTP_1_0)
                && !HttpHeaders.Values.KEEP_ALIVE.equalsIgnoreCase(request.headers().get(CONNECTION));
        if (!close && res.getManagedWriter() == null) {
            setContentLength(res, res.content().readableBytes());
        }
        ChannelFuture future;
        if (res.getManagedWriter() == null) {
            //if no post write op is set then the handler flushes the response
            future = ctx.writeAndFlush(res);
        } else {
            //if there is write manager it'll do all the write/flush
            future = res.doManagedWrite();
        }
        // Close the connection after the write operation is done if necessary.
        if (close) {
            future.addListener(ChannelFutureListener.CLOSE);
        }        //clean up and prep for next request. if keep-alive browsers like chrome will
        //make multiple requests on the same channel
        request = null;
        res = null;
        decoder = null;
        replied = true;
        return future;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        try {
            if (cause instanceof WebApplicationException) {
                writeResponse(ctx, cause, protocolConfig.getErrorTransformers());
            } else {
                log.warn(String.format("Error while processing request %s", request), cause);
                writeResponse(ctx, new WebApplicationException(HttpStatus.INTERNAL_SERVER_ERROR, request, cause),
                        protocolConfig.getErrorTransformers());
            }
        } catch (Throwable t) {
            //at this point if an exception occurs, just log and return internal server error
            //internal server error
            log.warn(String.format("Uncaught error while processing request %s", request), cause);
            res.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
            doWrite(ctx);
        }
    }
}
