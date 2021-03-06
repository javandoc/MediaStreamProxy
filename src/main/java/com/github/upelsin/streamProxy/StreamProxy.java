package com.github.upelsin.streamProxy;

import com.squareup.okhttp.Headers;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Okio;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.github.upelsin.streamProxy.Utils.*;


public class StreamProxy implements Runnable {

    private Logger logger = Logger.getLogger(StreamProxy.class.getName());

    private ServerSocket serverSocket;

    private Thread serverThread;

    private ExecutorService executor;

    private ForkedStreamFactory streamFactory;

    private Set<Socket> clientSockets = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private OkHttpClient client;

    public StreamProxy(ForkedStreamFactory streamFactory) {
        this.streamFactory = streamFactory;
    }

    public void start(int port) {
        try {
            serverSocket = new ServerSocket(port);
            serverSocket.setReuseAddress(port != 0);
        } catch (IOException e) {
            throw new ProxyNotStartedException(e);
        }

        client = new OkHttpClient();

        ThreadFactory threadFactory = ExceptionHandlingThreadFactory.loggingExceptionThreadFactory();
        executor = Executors.newCachedThreadPool(threadFactory);

        serverThread = threadFactory.newThread(this);
        serverThread.start();
    }

    public void start() {
        start(0);
    }

    public void shutdown() {
        if (serverThread == null) {
            throw new IllegalStateException("Cannot shutdown proxy, it has not been started");
        }

        executor.shutdownNow();
        closeClientSockets();

        serverThread.interrupt();
        closeQuietly(serverSocket);
        joinUninterruptibly(serverThread);

        serverThread = null;
    }

    private void closeClientSockets() {
        for (Iterator<Socket> s = clientSockets.iterator(); s.hasNext(); ) {
            closeQuietly(s.next());
            s.remove();
        }
    }

    @Override
    public void run() {
        if (serverThread == null) {
            throw new IllegalStateException("Proxy must be started first");
        }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                final Socket clientSocket = serverSocket.accept();
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        serveClientRequest(clientSocket);
                    }
                });

            } catch (RuntimeException e) { // protect while(){} from any runtime exception
                Thread t = Thread.currentThread();
                t.getUncaughtExceptionHandler().uncaughtException(t, e);

            } catch (IOException e) {
                logger.log(Level.WARNING, "Exception while accepting connection from client", e);
            }
        }
    }

    private void serveClientRequest(final Socket clientSocket) {
        clientSockets.add(clientSocket);
        BufferedSource source = null;

        try {
            source = Okio.buffer(Okio.source(clientSocket));

            String url = parseGetRequestUrl(source);
            Properties queryParams = parseQueryParams(url);
            Response response = executeRealRequest(source, url);

            if (Thread.currentThread().isInterrupted()) return;

            writeClientResponse(clientSocket, response, queryParams);

        } catch (IOException e) {
            logger.log(Level.WARNING, "Exception while serving client request", e);

        } finally {
            closeQuietly(source);
            clientSockets.remove(clientSocket);
        }
    }

    private Properties parseQueryParams(String url) throws UnsupportedEncodingException {
        Properties queryParams = new Properties();
        Map<String, List<String>> mappedParams = getUrlParameters(url);
        for (Map.Entry<String, List<String>> entry : mappedParams.entrySet()) {
            queryParams.setProperty(entry.getKey(), entry.getValue().get(0));
        }

        return queryParams;
    }

    private String parseGetRequestUrl(BufferedSource source) throws IOException {
        String requestLine = source.readUtf8LineStrict();
        StringTokenizer st = new StringTokenizer(requestLine);

        String method = st.nextToken();
        if (!method.equalsIgnoreCase("GET")) {
            throw new ProxyRequestNotSupportedException("Unable to serve request, only GET is supported");
        }

        String url = st.nextToken();
        return url.substring(1); // skip leading "/"
    }

    private Headers.Builder buildHeaders(BufferedSource source) throws IOException {
        Headers.Builder headers = new Headers.Builder();
        String header;
        while ((header = source.readUtf8LineStrict()).length() != 0) {
            headers.add(header);
        }
        return headers;
    }

    private Response executeRealRequest(BufferedSource source, String realUri) throws IOException {
        Headers.Builder headers = buildHeaders(source);
        Request request = new Request.Builder()
                .url(realUri)
                .headers(headers.build())
                .build();
        return client.newCall(request).execute();
    }

    private void writeClientResponse(Socket clientSocket, Response response, Properties props) throws IOException {
        ForkedStream forkedStream = streamFactory.createForkedStream(props);
        try {
            writeResponse(clientSocket, response, forkedStream);
        } catch (IOException e) {
            forkedStream.abort();
            throw e;
        } finally {
            if (Thread.currentThread().isInterrupted()) {
                // might be called twice, but that's fine
                forkedStream.abort();
            }
        }
    }

    private void writeResponse(Socket clientSocket, Response response, ForkedStream forkedStream)
            throws IOException {

        BufferedSource source = response.body().source();
        BufferedSink sink = Okio.buffer(Okio.sink(clientSocket));

        try {
            writeStatusLine(response, sink);
            writeHeaders(response, sink);
            sink.flush();

            byte[] buffer = new byte[16384];
            while (!Thread.currentThread().isInterrupted()) {
                int read = source.read(buffer);
                if (read == -1) {
                    break;
                }

                sink.write(buffer, 0, read);
                sink.flush();

                forkedStream.write(buffer, 0, read);
                forkedStream.flush();
            }
        } finally {
            closeQuietly(source);
            closeQuietly(sink);
            closeQuietly(forkedStream);
        }
    }

    private void writeStatusLine(Response response, BufferedSink sink) throws IOException {
        String protocol = response.protocol().toString().toUpperCase(Locale.US);
        String statusLine = String.format("%s %d %s\r\n", protocol, response.code(), response.message());
        sink.writeUtf8(statusLine);
    }

    private void writeHeaders(Response response, BufferedSink sink) throws IOException {
        Headers headers = response.headers();
        for (int i = 0, size = headers.size(); i < size; i++) {
            sink.writeUtf8(headers.name(i));
            sink.writeUtf8(": ");
            sink.writeUtf8(headers.value(i));
            sink.writeUtf8("\r\n");
        }
        sink.writeUtf8("\r\n");
    }

    public int getPort() {
        if (serverThread == null) {
            throw new IllegalStateException("Proxy must be started before obtaining port number");
        }

        return serverSocket.getLocalPort();
    }

    public ForkedStreamFactory getForkedStreamFactory() {
        return streamFactory;
    }
}
