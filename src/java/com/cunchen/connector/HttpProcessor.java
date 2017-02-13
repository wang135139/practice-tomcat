package com.cunchen.connector;

import com.cunchen.connector.HttpConnector;
import com.cunchen.server.header.HttpHeader;
import com.cunchen.server.io.HttpRequest;
import com.cunchen.server.io.HttpResponse;
import com.cunchen.server.io.RequestLine;
import com.cunchen.server.io.SocketInputStream;
import com.cunchen.server.processor.ServletProcessor;
import com.cunchen.server.processor.StaticResourceProcessor;
import com.cunchen.util.RequestUtil;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.logging.Logger;

/**
 * Http解析器
 * Created by wqd on 2016/12/28.
 */
public class HttpProcessor implements Runnable {

    private static final Logger log = Logger.getLogger(HttpProcessor.class.getName());

    private HttpRequest request;
    private HttpResponse response;


    private String uri;
    private String protocol;

    private RequestLine requestLine;


    private boolean available;                  //线程可用
    private Socket socket;                      //当前线程

    private int debug;

    private HttpConnector connector;

    private HttpProcessor threadSync;

    public HttpProcessor(HttpConnector httpConnector) {
        this.available = false;
        this.requestLine = new RequestLine();
        this.connector = httpConnector;
        this.threadSync = this;
    }

    public void process(Socket socket) {

        boolean ok, finishResponse, keepAlive, stopped, http11;

        SocketInputStream input = null;
        OutputStream output = null;
        try {
            input = new SocketInputStream(socket.getInputStream(), connector.getBufferSize());
            request = new HttpRequest(input);
        } catch (Exception e) {
            ok = false;
        }

        try {
            output = socket.getOutputStream();
            response = new HttpResponse(output);
            response.setRequest(request);
            response.setHeader("Server", "Pyrmont Servlet Container");

            parseRequest(input, output);
            parseHeaders(input);

            if(request.getRequestURI().startsWith("/servlet/")) {
                ServletProcessor processor = new ServletProcessor();
                processor.process(request, response);
            } else {
                StaticResourceProcessor processor = new StaticResourceProcessor();
                processor.process(request, response);
            }

            socket.close();

        } catch (IOException | ServletException | NullPointerException e ) {
            e.printStackTrace();
        }
    }

    /**
     * Io流Request解析
     *
     * @param input  输入流
     * @param output 输出流
     */
    private void parseRequest(SocketInputStream input, OutputStream output) throws ServletException, NullPointerException {
        input.readRequestLine(requestLine);
        String method = new String(requestLine.method, 0, requestLine.methodEnd);

        if (method.length() < 1) {
            throw new ServletException("Missing HTTP request method");
        } else if (requestLine.uriEnd < 1) {
            throw new ServletException("Missing HTTP request URI");
        }

        int question = requestLine.indexOf("?");
        if (question >= 0) {
            request.setQueryString(new String(requestLine.uri, question + 1, requestLine.uriEnd - question - 1));
            uri = new String(requestLine.uri, 0, question);
        } else {
            request.setQueryString(null);
            uri = new String(requestLine.uri, 0, requestLine.uriEnd);
        }

        request.setUri(uri);

        //Checking for an absolute URI
        if (!uri.startsWith("/")) {
            int pos = uri.indexOf("://");
            //Parsing out protocol and hsot name
            if (pos != -1) {
                uri = "";
            } else {
                uri = uri.substring(pos);
            }

            String match = ";jessionid=";
            int semicolon = uri.indexOf(match);
            if (semicolon >= 0) {
                String rest = uri.substring(semicolon + match.length());
                int semicolon2 = rest.indexOf(';');
                if (semicolon2 >= 0) {
                    request.setRequestedSessionId(rest.substring(0, semicolon2));
                    rest = rest.substring(semicolon2);
                } else {
                    request.setRequestedSessionId(rest);
                    rest = "";
                }
                request.setRequestedSessionURL(true);
                uri = uri.substring(0, semicolon) + rest;
            } else {
                request.setRequestedSessionId(null);
                request.setRequestedSessionURL(false);
            }

            //Normalize URI
            String normalizedUri = normalize(uri);

            //Set the corresponding request properties
            ((HttpRequest) request).setMethod(method);
            request.setProtocol(protocol);
            if (normalizedUri != null) {
                ((HttpRequest) request).setRequestURI(normalizedUri);
            } else {
                ((HttpRequest) request).setRequestURI(uri);
            }

            if (normalizedUri == null) {
                throw new ServletException("Invalid URI: " + uri + "'");
            }
        }
    }

    /**
     * normalize处理化
     * @param uri
     * @return
     */
    private String normalize(String uri) {
        return null;
    }

    /**
     * 头解析
     * 循环读入Io流，直到所有头部读取完成
     * @param input 输入流
     */
    private void parseHeaders(SocketInputStream input) throws ServletException {

        HttpHeader header = null;
        for (header = new HttpHeader(); input.readHeader(header); header = new HttpHeader()) {

            if (header.nameEnd == 0) {
                if (header.valueEnd == 0) {
                    return;
                } else {
                    throw new ServletException("Parse Header errror!");
                }
            }

            String name = new String(header.name, 0, header.nameEnd);
            String value = new String(header.value, 0, header.valueEnd);

            //加入HashMap
            request.addHeader(name, value);

            if (name.equals("cookie")) {
                parseCookie(value);
            } else if (name.equals("content-length")) {
                int n = -1;
                try {
                    n = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new ServletException(("Parse Header Content Length Exception!"));
                }
                request.setContentLength(n);
            } else if (name.equals("content-type")) {
                request.setContentType(value);
            }
        }

    }

    private void parseCookie(String value) {
        Cookie cookies[] = RequestUtil.parseCookieHeader(value);
        for (int i = 0; i < cookies.length; i++) {
            if(cookies[i].getName().equals("jsessionid")) {
                //Accept only the first session id cookie
                request.setRequestedSessionId(cookies[i].getValue());
                request.setRequestedSessionCookie(true);
                request.setRequestedSessionURL(false);
            }
            request.addCookie(cookies[i]);
        }
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    /**
     * TODO
     * 请求
     * @param socket
     */
    synchronized void assign(Socket socket) {

        while(available) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        this.socket = socket;
        available = true;
        notifyAll();
    }

    /**
     * HttpProcessor run方法
     */
    @Override
    public void run() {
        boolean stopped = false;
        while(!stopped) {
            Socket socket = await();
            if(socket == null)
                continue;
            try {
                process(socket);
            } catch (Throwable t) {
                t.printStackTrace();
            }

            connector.recycle(this);
        }

        synchronized (threadSync) {
            threadSync.notifyAll();
        }
    }

    /**
     * 等待Connector提供一个新Socket
     * @return
     */
    private synchronized Socket await() {
        while(!available) {
            try {
                wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        //通知连接器已经接收到Socket
        Socket socket = this.socket;
        available = false;
        notifyAll();
        if((debug >= 1) && (socket != null))
            log.info("The incoming request has been awaited");
        return socket;

    }
}
