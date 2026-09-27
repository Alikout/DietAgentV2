(function () {
    "use strict";

    const API_BASE = "/api/v1/diet";
    const AUTH_BASE = "/api/v1/auth";
    const TOKEN_KEY = "diet.token";
    const USERNAME_KEY = "diet.username";
    const ROLE_KEY = "diet.role";
    const SESSION_KEY = "diet.sessionId";
    const DEFAULT_TIMEOUT_MS = 30000;

    // ---------- 认证凭证（第四周 JWT）：服务端只认 Authorization 头，不再发 X-User-Id ----------
    function getToken() {
        return localStorage.getItem(TOKEN_KEY) || "";
    }

    function setToken(token) {
        if (token) {
            localStorage.setItem(TOKEN_KEY, token);
        } else {
            localStorage.removeItem(TOKEN_KEY);
        }
    }

    function getUsername() {
        return localStorage.getItem(USERNAME_KEY) || "";
    }

    function setUsername(username) {
        if (username) {
            localStorage.setItem(USERNAME_KEY, username);
        } else {
            localStorage.removeItem(USERNAME_KEY);
        }
    }

    /** 角色：USER（默认，对话+个人库+公共库只读）/ ADMIN（公共库管理+Trace+评估）。 */
    function getRole() {
        return localStorage.getItem(ROLE_KEY) || "USER";
    }

    function setRole(role) {
        if (role) {
            localStorage.setItem(ROLE_KEY, role);
        } else {
            localStorage.removeItem(ROLE_KEY);
        }
    }

    function isLoggedIn() {
        return Boolean(getToken());
    }

    // ---------- 会话持久化（第三周）：刷新页面不丢会话 ----------
    function getSessionId() {
        return localStorage.getItem(SESSION_KEY) || "";
    }

    function setSessionId(sessionId) {
        if (sessionId) {
            localStorage.setItem(SESSION_KEY, sessionId);
        } else {
            localStorage.removeItem(SESSION_KEY);
        }
    }

    function sleep(ms) {
        return new Promise((resolve) => setTimeout(resolve, ms));
    }

    /** 请求超时信号：优先原生 AbortSignal.timeout，降级 AbortController + 定时器（第三周止血包）。 */
    function createTimeoutSignal(timeoutMs) {
        if (typeof AbortSignal !== "undefined" && typeof AbortSignal.timeout === "function") {
            return { signal: AbortSignal.timeout(timeoutMs), cancel: () => {} };
        }
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), timeoutMs);
        return { signal: controller.signal, cancel: () => clearTimeout(timer) };
    }

    function buildHeaders(withJsonBody) {
        const headers = new Headers();
        const token = getToken();
        if (token) {
            headers.set("Authorization", `Bearer ${token}`);
        }
        if (withJsonBody) {
            headers.set("Content-Type", "application/json");
        }
        return headers;
    }

    async function readError(response) {
        const text = await response.text();
        if (!text) {
            return "";
        }
        try {
            const payload = JSON.parse(text);
            return payload.message || payload.error || text;
        } catch (error) {
            return text;
        }
    }

    /** 带超时与 JWT 的通用请求；401 抛出带 status 的错误，由页面层切换登录视图。 */
    async function request(path, options) {
        const config = options || {};
        const { signal, cancel } = createTimeoutSignal(config.timeoutMs || DEFAULT_TIMEOUT_MS);
        try {
            const response = await fetch(`${API_BASE}${path}`, {
                method: config.method || "GET",
                headers: buildHeaders(config.body !== undefined),
                body: config.body === undefined ? undefined : JSON.stringify(config.body),
                signal
            });

            if (response.status === 401) {
                const unauthorized = new Error("登录已过期，请重新登录");
                unauthorized.status = 401;
                throw unauthorized;
            }
            if (!response.ok) {
                const failure = new Error(await readError(response) || `请求失败：${response.status}`);
                failure.status = response.status;
                throw failure;
            }
            if (response.status === 204) {
                return null;
            }
            const text = await response.text();
            if (!text) {
                return null;
            }
            try {
                return JSON.parse(text);
            } catch (error) {
                return text;
            }
        } finally {
            cancel();
        }
    }

    /**
     * SSE 流式对话（第三周）：POST /chat/stream，用 ReadableStream 手动解析 SSE 帧
     * （原生 EventSource 不支持 POST 与 Authorization 头）。
     * 返回值约定：
     *   - 正常结束：返回 done 帧的完整 ChatResponse；
     *   - 返回 null：content-type 不是 text/event-stream（端点不可用/代理缓冲），调用方应降级普通 /chat；
     *   - error 帧 / HTTP 错误：抛出带 message 的错误，不做降级（消息可能已被服务端处理）。
     */
    async function chatStream(payload, handlers) {
        const options = handlers || {};
        const response = await fetch(`${API_BASE}/chat/stream`, {
            method: "POST",
            headers: buildHeaders(true),
            body: JSON.stringify(payload)
        });

        if (response.status === 401) {
            const unauthorized = new Error("登录已过期，请重新登录");
            unauthorized.status = 401;
            throw unauthorized;
        }
        if (!response.ok) {
            const failure = new Error(await readError(response) || `请求失败：${response.status}`);
            failure.status = response.status;
            throw failure;
        }

        const contentType = response.headers.get("content-type") || "";
        if (!contentType.includes("text/event-stream")) {
            return null;
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";
        let finalResponse = null;
        let errorMessage = null;

        while (true) {
            const { done, value } = await reader.read();
            if (done) {
                break;
            }
            buffer += decoder.decode(value, { stream: true });
            let frameEnd;
            while ((frameEnd = buffer.indexOf("\n\n")) !== -1) {
                const frame = buffer.slice(0, frameEnd);
                buffer = buffer.slice(frameEnd + 2);
                const eventName = /event:\s*(.+)/.exec(frame);
                const dataLine = /data:\s*(.+)/.exec(frame);
                if (!eventName || !dataLine) {
                    continue;
                }
                const event = eventName[1].trim();
                let data = null;
                try {
                    data = JSON.parse(dataLine[1].trim());
                } catch (error) {
                    data = null;
                }
                if (event === "stage") {
                    if (options.onStage) {
                        options.onStage(data);
                    }
                } else if (event === "done") {
                    finalResponse = data;
                } else if (event === "error") {
                    errorMessage = data && data.message ? data.message : "服务异常";
                }
            }
        }

        if (errorMessage) {
            const failure = new Error(errorMessage);
            failure.fromStream = true;
            throw failure;
        }
        return finalResponse;
    }

    // ---------- 认证接口（第四周）：成功后自动保存 token 与用户名 ----------
    async function authRequest(path, body) {
        const response = await fetch(`${AUTH_BASE}${path}`, {
            method: "POST",
            headers: buildHeaders(true),
            body: JSON.stringify(body)
        });
        if (!response.ok) {
            const failure = new Error(await readError(response) || `认证失败：${response.status}`);
            failure.status = response.status;
            throw failure;
        }
        const data = await response.json();
        setToken(data.token);
        setUsername(data.username);
        setRole(data.role || "USER");
        return data;
    }

    function toQuery(params) {
        const search = new URLSearchParams();
        Object.entries(params || {}).forEach(([key, value]) => {
            if (value !== undefined && value !== null && value !== "") {
                search.set(key, value);
            }
        });
        const query = search.toString();
        return query ? `?${query}` : "";
    }

    window.DietApi = {
        getToken,
        getUsername,
        getRole,
        isLoggedIn,
        getSessionId,
        setSessionId,
        sleep,
        login: (username, password) => authRequest("/login", { username, password }),
        register: (username, password) => authRequest("/register", { username, password }),
        logout: () => {
            setToken("");
            setUsername("");
            setRole("");
            setSessionId("");
        },
        createSession: () => request("/sessions", { method: "POST" }),
        chat: (payload) => request("/chat", { method: "POST", body: payload }),
        chatStream,
        getSessionMessages: (sessionId, limit) => request(`/sessions/${encodeURIComponent(sessionId)}/messages${toQuery({ limit })}`),
        listPersonalMeals: () => request("/meals/personal"),
        createPersonalMeal: (payload) => request("/meals/personal", { method: "POST", body: payload }),
        updatePersonalMeal: (mealId, payload) => request(`/meals/personal/${encodeURIComponent(mealId)}`, { method: "PUT", body: payload }),
        deletePersonalMeal: (mealId) => request(`/meals/personal/${encodeURIComponent(mealId)}`, { method: "DELETE" }),
        listPublicMeals: () => request("/meals/public"),
        createPublicMeal: (payload) => request("/meals/public", { method: "POST", body: payload }),
        updatePublicMeal: (mealId, payload) => request(`/meals/public/${encodeURIComponent(mealId)}`, { method: "PUT", body: payload }),
        deletePublicMeal: (mealId) => request(`/meals/public/${encodeURIComponent(mealId)}`, { method: "DELETE" }),
        slotOptions: () => request("/slot-options"),
        saveFeedback: (payload) => request("/feedback", { method: "POST", body: payload }),
        listTraces: (params) => request(`/debug/traces${toQuery(params)}`),
        getTrace: (traceId) => request(`/debug/traces/${encodeURIComponent(traceId)}`),
        listSessionTraces: (sessionId, limit) => request(`/debug/sessions/${encodeURIComponent(sessionId)}/traces${toQuery({ limit })}`),
        labelTrace: (traceId, payload) => request(`/debug/traces/${encodeURIComponent(traceId)}/label`, { method: "PUT", body: payload }),
        evaluate: (payload) => request("/evaluations", { method: "POST", body: payload, timeoutMs: 120000 }),
        submitEvaluationJob: (payload) => request("/evaluations/jobs", { method: "POST", body: payload }),
        getEvaluationJob: (jobId) => request(`/evaluations/jobs/${encodeURIComponent(jobId)}`)
    };
})();
