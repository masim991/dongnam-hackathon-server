export default {
  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    if (path === "/" || path === "/api/health") {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return Response.json({ error: "Method not allowed" }, {
          status: 405, headers: { Allow: "GET, HEAD" },
        });
      }
      const response = Response.json({ ok: true, service: "dongnam-hackathon" }, {
        headers: { "Cache-Control": "no-store" },
      });
      return request.method === "HEAD"
        ? new Response(null, { headers: response.headers })
        : response;
    }
    return Response.json({ error: "Not found" }, { status: 404 });
  },
};
