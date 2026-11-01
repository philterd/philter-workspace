# LLM-as-a-Judge

**Explain** and **Ask AI** on the review page send a document to an Ollama
instance for a second opinion on what Philter detected. Ollama instances and
the defaults the two actions use are set under **Admin → LLM-as-a-Judge**.

Each call sends the full unredacted document text and the detected values to
Ollama, and is recorded in the audit log as `DOCUMENT_PII_SENT_TO_LLM` before
it is sent. See the [API reference](../reference/api.md#llm-as-a-judge).

## Ollama instances

**Add Ollama instance** takes a name, an endpoint, and a port (Ollama's is
11434). The endpoint is a host name or address, optionally with `https://` in
front; without a scheme, Philter Workspace uses `http://`. **Test** checks
that the instance answers.

An endpoint must pass the [host allow-list](data-sources.md#trust-model-and-host-allow-list),
which blocks private-network addresses unless they are allowed.

## Defaults

**Defaults** chooses the instance and model **Explain** uses, and the instance
and model **Ask AI** uses.

## HTTPS for an instance on another machine

Ollama serves plain HTTP. When it runs on another machine, the document text
sent to it crosses the network unencrypted, so the page warns about an
`http` endpoint on another machine, both when it is added and in the list of
instances. An endpoint on this machine (`localhost`, `127.0.0.1`, `::1`, or
`host.docker.internal`) or a compose service name such as `ollama` is not
warned about, since its traffic does not leave the machine. Any name without
a dot is taken to be a compose service, so a short name that resolves to
another machine on your network is not warned about either; use HTTPS for it
all the same.

For an instance on another machine:

1. On that machine, bind Ollama to loopback only (`OLLAMA_HOST=127.0.0.1:11434`),
   so it cannot be reached over the network without TLS.
2. Run a reverse proxy that terminates TLS on that machine, such as Caddy or
   nginx, and forward its HTTPS port to `127.0.0.1:11434`. With Caddy, for
   example:

        ollama.example.com {
            reverse_proxy 127.0.0.1:11434
        }

3. Add the instance with the endpoint `https://ollama.example.com` and the
   proxy's port (443).

Philter Workspace checks the proxy's certificate against the Java runtime's
trusted certificates. A certificate from a public certificate authority works
as it is. For one from a private authority, add the authority's certificate to
a truststore and point the Java runtime at it, for example with
`JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/path/to/truststore.p12
-Djavax.net.ssl.trustStorePassword=...`; the truststore must then also hold any
public authorities Philter Workspace needs.

Keep request-body logging off on Ollama (`OLLAMA_DEBUG=0`), so the document
text is not written to Ollama's logs.
