# HttpRequest

[![](https://jitpack.io/v/Edsuns/HttpRequest.svg)](https://jitpack.io/#Edsuns/HttpRequest)

A small-size but efficient library makes http request easier for java.

## Demo

#### Normal Usage

```java
public class Demo {
    public static void main(String[] args) throws IOException {
        HttpRequest request = new HttpRequest(url)
                // set request headers
                .headers(HttpRequest.data("Cache-Control", "no-cache")
                        .data("Accept-Encoding", "deflate")
                )
                // set cookies
                .cookies(cookiesIn)
                // other configurations
                .timeout(2000)
                .followRedirects(true)
                // get request with url params
                .get(HttpRequest.data("name", "value")
                        // support for type conversion
                        .data("integer", 1)
                );
        int status = request.getStatus();// response status
        String body = request.getBody();// body content
        byte[] bodyBytes = request.getBodyBytes();// body bytes
        List<String> redirects = request.getRedirects();// redirect url list
        Map<String, List<String>> responseHeaders =
                request.getResponseHeaders();// response headers
        Map<String, String> cookies = request.getCookies();// all the cookies
    }
}
```

#### Request Methods

```java
public class Demo {
    public static void main(String[] args) throws IOException {
        HttpRequest request = new HttpRequest(url)
                // post request with form data
                .exec(HttpRequest.Method.POST,// also support other request methods
                        HttpRequest.data("name", "value")
                                .data("integer", 1)
                );
        String body = request.getBody();
        Map<String, String> cookies = request.getCookies();
    }
}
```

#### Use Proxy

```java
public class Demo {
    public static void main(String[] args) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                new InetSocketAddress(proxyHost, proxyPort));
        HttpRequest request = new HttpRequest(url, proxy)
                .timeout(timeout).exec(HttpRequest.Method.HEAD);
        int status = request.getStatus();
    }
}
```

#### Async Request

```java
public class Demo {
    public static void main(String[] args) {
        HttpRequest.async(new HttpRequest(URL_NEED_PROXY).timeout(timeout),
                HttpRequest.Method.HEAD)
                .observe(request -> {// when request success
                    int status = request.getStatus();
                }, exception -> {// when an exception is caught
                    String msg = exception.getMessage();
                });
    }
}
```

## How To

__Step 1.__ Add the JitPack repository to your build file

Add it in your root build.gradle at the end of repositories:

```groovy
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

__Step 2.__ Add the dependency

```groovy
dependencies {
    implementation 'com.github.Edsuns:HttpRequest:Tag'
}
```
