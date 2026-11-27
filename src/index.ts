import { handleCourseApi, type Env } from "./courses.ts";
import { handleWeather } from "./weather-api.ts";

export default {
  async fetch(request: Request, env: Env = {}): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
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
    if (path.startsWith("/api/courses") || path === "/api/crosswalks" || path === "/api/signals") {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return Response.json({ error: "Method not allowed" }, { status: 405, headers: { Allow: "GET, HEAD" } });
      }
      const response = await handleCourseApi(url, env);
      if (response) return request.method === "HEAD" ? new Response(null, { status: response.status, headers: response.headers }) : response;
    }
    if (path === "/api/weather") {
      if (request.method !== "GET" && request.method !== "HEAD") {
        return Response.json({ error: "Method not allowed" }, { status: 405, headers: { Allow: "GET, HEAD" } });
      }
      const response = await handleWeather(url);
      return request.method === "HEAD" ? new Response(null, { status: response.status, headers: response.headers }) : response;
    }
    return Response.json({ error: "Not found" }, { status: 404 });
  },
};
