(function () {
    "use strict";
    const app = document.getElementById("app");
    const toast = document.getElementById("toast");
    const userField = document.getElementById("userField");
    const SLOT_LABELS = {
        mealTime: "用餐时间",
        mood: "心情状态",
        scene: "用餐场景",
        healthGoal: "健康目标",
        cuisine: "菜系偏好",
        taste: "口味偏好",
        convenience: "便利程度"
    };
    const INTENTS = [
        "MEAL_RECOMMENDATION",
        "CLARIFY_NEEDED",
        "MEAL_ADJUST",
        "MEAL_PLAN",
        "HEALTH_RISK",
        "OTHER"
    ];
    // 阶段事件 → 用户可读文案（第三周"让等待可见"）：文案在前端维护，后端只发中性事件
    const STAGE_LABELS = {
        REQUEST_RECEIVED: "收到，正在理解你的需求…",
        USER_MESSAGE_RECORDED: "收到，正在理解你的需求…",
        INTENT_RECOGNIZED: "正在确认你的口味与场景…",
        INTENT_REVISED: "正在确认你的口味与场景…",
        ROUTE_SELECTED: "正在选择推荐策略…",
        SLOTS_MERGED: "正在整合你的偏好…",
        CLARIFY_DECISION: "正在判断是否需要追问…",
        MEAL_SEARCHED: "正在挑选餐食…",
        MEAL_RANKED: "候选已就绪，正在生成推荐语…",
        RECOMMEND_RESULT_BUILT: "正在生成推荐理由…",
        RESPONSE_AGENT_RESULT: "正在组织回复…",
        PLAN_CONTEXT_RESOLVED: "正在拆解多餐规划…",
        MEAL_PLAN_SEARCHED: "正在为每个餐次挑选餐食…",
        ADJUST_CONTEXT_RESOLVED: "正在按你的要求调整…"
    };
    const state = {
        auth: { mode: "login" },
        slotOptions: null,
        personalMeals: [],
        publicMeals: [],
        editingMeal: null,
        editingPublicMeal: null,
        chat: {
            sourceMode: "PERSONAL",
            sessionId: DietApi.getSessionId() || null,
            sending: false,
            historyLoaded: false,
            pendingRequestId: null,
            lastAssistantText: null,
            messages: [
                {
                    role: "assistant",
                    text: "你好，我可以根据你的个人餐食库或公共餐食库推荐今天吃什么。可以试试问我：今晚想吃清淡一点，有什么推荐？"
                }
            ]
        },
        traces: {
            rows: [],
            selected: null,
            loading: false,
            filters: defaultTraceFilters()
        },
        evaluation: {
            report: null,
            loading: false,
            form: defaultRangeForm()
        }
    };
    function defaultRangeForm() {
        const end = new Date();
        const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
        return {
            startAt: toLocalInputValue(start),
            endAt: toLocalInputValue(end),
            limit: 50,
            includeLlmJudge: false
        };
    }
    function defaultTraceFilters() {
        const range = defaultRangeForm();
        return {
            startAt: range.startAt,
            endAt: range.endAt,
            onlyUnlabeled: false,
            limit: 50,
            sessionId: ""
        };
    }
    function toLocalInputValue(date) {
        const pad = (value) => String(value).padStart(2, "0");
        return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    }
    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll('"', "&quot;")
            .replaceAll("'", "&#039;");
    }
    function safeJson(value) {
        if (value === null || value === undefined || value === "") {
            return "";
        }
        try {
            const parsed = typeof value === "string" ? JSON.parse(value) : value;
            return JSON.stringify(parsed, null, 2);
        } catch (error) {
            return String(value);
        }
    }
    function showToast(message, type) {
        toast.textContent = message;
        toast.className = `toast show ${type === "error" ? "error" : ""}`;
        window.clearTimeout(showToast.timer);
        showToast.timer = window.setTimeout(() => {
            toast.className = "toast";
        }, 3200);
    }
    /** 统一错误出口：401 切回登录视图，403 提示无权限，其余 toast 提示。 */
    function showErrorOrAuth(error, fallbackMessage) {
        if (error && error.status === 401) {
            logoutToAuth("登录已过期，请重新登录");
            return;
        }
        if (error && error.status === 403) {
            showToast("该功能仅管理员可用", "error");
            return;
        }
        showToast((error && error.message) || fallbackMessage, "error");
    }
    function setLoading(button, loadingText) {
        if (!button) {
            return () => {};
        }
        const oldText = button.textContent;
        button.disabled = true;
        button.textContent = loadingText || "处理中...";
        return () => {
            button.disabled = false;
            button.textContent = oldText;
        };
    }
    async function guard(action, successMessage) {
        try {
            const result = await action();
            if (successMessage) {
                showToast(successMessage);
            }
            return result;
        } catch (error) {
            showErrorOrAuth(error, "操作失败");
            throw error;
        }
    }
    function currentRoute() {
        return (location.hash || "#/diet").slice(1).split("?")[0] || "/diet";
    }
    function navigate(route) {
        location.hash = route;
    }
    function setActiveNav(route) {
        document.querySelectorAll("[data-nav]").forEach((item) => {
            item.classList.toggle("active", item.dataset.nav === route);
        });
    }
    /** 当前登录者是否管理员。 */
    function isAdmin() {
        return DietApi.getRole() === "ADMIN";
    }

    /** 各角色登录后的默认落地页：用户=对话界面，管理员=公共库管理。 */
    function defaultRoute() {
        return isAdmin() ? "/diet/meals/public" : "/diet/chat";
    }

    /** 导航按角色显隐：data-role 标记的链接只对对应角色可见。 */
    function applyRoleToNav() {
        const role = DietApi.getRole();
        document.querySelectorAll("[data-role]").forEach((item) => {
            item.style.display = item.dataset.role === role ? "" : "none";
        });
    }

    function render() {
        if (!DietApi.isLoggedIn()) {
            renderAuth();
            return;
        }
        applyRoleToNav();
        const route = currentRoute();
        // 角色路由守卫：越权路由自动落回各自首页（后端 Security 规则兜底）
        if (isAdmin()) {
            if (route === "/diet" || route === "/diet/chat" || route === "/diet/meals/personal") {
                navigate("/diet/meals/public");
                return;
            }
        } else if (route.startsWith("/admin")) {
            navigate("/diet/chat");
            return;
        }
        setActiveNav(route);
        if (route === "/diet" || route === "/diet/chat") {
            renderChat();
        } else if (route === "/diet/meals/personal") {
            renderPersonalMeals();
        } else if (route === "/diet/meals/public") {
            renderPublicMeals();
        } else if (route === "/admin/traces") {
            renderTraces();
        } else if (route === "/admin/evaluations") {
            renderEvaluations();
        } else {
            navigate(defaultRoute());
        }
        app.focus({ preventScroll: true });
    }

    // ================= 登录/注册（第四周鉴权） =================
    function renderAuth(message) {
        userField.innerHTML = "";
        app.innerHTML = `
            <section class="hero" style="min-height: 62vh;">
                <div class="hero-panel" style="max-width: 480px;">
                    <span class="badge">饮食推荐助手</span>
                    <h1>${state.auth.mode === "register" ? "创建账号" : "登录"}</h1>
                    <p class="muted">${escapeHtml(message || "登录后，让助手帮你决定今天吃什么。")}</p>
                    <form id="authForm" class="form-grid" style="margin-top: 12px;">
                        <div class="field full">
                            <label for="authUsername">用户名</label>
                            <input id="authUsername" name="username" required autocomplete="username">
                        </div>
                        <div class="field full">
                            <label for="authPassword">密码（至少 6 位）</label>
                            <input id="authPassword" name="password" type="password" required minlength="6" autocomplete="current-password">
                        </div>
                        <div class="field full">
                            <div class="button-row">
                                <button class="btn primary" type="submit">${state.auth.mode === "register" ? "注册并登录" : "登录"}</button>
                                <button class="btn ghost" type="button" data-action="toggle-auth-mode">${state.auth.mode === "register" ? "已有账号，去登录" : "注册新账号"}</button>
                            </div>
                        </div>
                    </form>
                </div>
            </section>
        `;
    }
    async function submitAuth(form) {
        const formData = new FormData(form);
        const username = String(formData.get("username") || "").trim();
        const password = String(formData.get("password") || "");
        const restore = setLoading(form.querySelector("button[type=submit]"), "处理中...");
        try {
            if (state.auth.mode === "register") {
                await DietApi.register(username, password);
            } else {
                await DietApi.login(username, password);
            }
            state.auth.mode = "login";
            initUserBar();
            navigate(defaultRoute());
            render();
            showToast("欢迎，" + (DietApi.getUsername() || "用户"));
        } catch (error) {
            showToast(error.message || "认证失败", "error");
        } finally {
            restore();
        }
    }
    /** 退出登录（或 401 失效）：清凭证与本地会话，切回登录视图。 */
    function logoutToAuth(toastMessage) {
        DietApi.logout();
        state.auth.mode = "login";
        state.chat.sessionId = null;
        state.chat.historyLoaded = false;
        state.chat.pendingRequestId = null;
        state.personalMeals = [];
        state.publicMeals = [];
        state.editingPublicMeal = null;
        state.traces.rows = [];
        state.traces.selected = null;
        renderAuth(toastMessage);
    }
    function initUserBar() {
        if (!DietApi.isLoggedIn()) {
            userField.innerHTML = "";
            return;
        }
        userField.innerHTML = `
            <span class="muted">你好，<strong>${escapeHtml(DietApi.getUsername() || "用户")}</strong></span>
            <button class="btn ghost" data-action="logout">退出登录</button>
        `;
    }

    // ================= 通用小组件 =================
    function statCard(label, value, desc) {
        return `
            <div class="stat-card">
                <span class="muted">${escapeHtml(label)}</span>
                <strong>${escapeHtml(value)}</strong>
                <p class="muted">${escapeHtml(desc)}</p>
            </div>
        `;
    }

    // ================= 聊天推荐（第三周流式体验） =================
    function renderChat() {
        app.innerHTML = `
            <section class="chat-layout">
                <div class="section chat-window">
                    <div class="card-title">
                        <div>
                            <h2>聊天推荐</h2>
                            <p>告诉我你想吃什么，我来帮你挑</p>
                        </div>
                        <div class="inline-actions">
                            <button class="btn ${state.chat.sourceMode === "PERSONAL" ? "soft" : "ghost"}" data-action="set-source" data-source="PERSONAL">个人库</button>
                            <button class="btn ${state.chat.sourceMode === "PUBLIC" ? "soft" : "ghost"}" data-action="set-source" data-source="PUBLIC">公共库</button>
                            <button class="btn ghost" data-action="new-session">新会话</button>
                        </div>
                    </div>
                    <div id="messages" class="messages">${state.chat.messages.map(renderMessage).join("")}</div>
                    <form id="chatForm" class="composer">
                        <textarea name="message" placeholder="例如：今晚想吃清淡一点，最好快手一点" required></textarea>
                        <button class="btn primary" type="submit">${state.chat.sending ? "发送中..." : "发送"}</button>
                    </form>
                </div>
                <aside class="grid">
                    <div class="card">
                        <div class="card-title">
                            <div>
                                <h3>快捷问题</h3>
                                <p>点击后可直接填入输入框。</p>
                            </div>
                        </div>
                        <div class="chips">
                            ${["早餐想吃方便一点", "晚饭推荐清淡低脂的", "今天心情一般，想吃点热乎的", "换一批，不想吃刚才那些", "我胃不舒服，应该吃什么"].map((text) => `<button class="chip" data-action="quick-message" data-message="${escapeHtml(text)}">${escapeHtml(text)}</button>`).join("")}
                        </div>
                    </div>
                    <div class="card">
                        <h3>使用提示</h3>
                        <p class="muted">从公共餐食库看到喜欢的菜，点卡片上的「加入个人库」就能保存下来，个人库里的菜会被优先推荐。刷新页面会话不丢失。</p>
                        <div class="button-row">
                            <a class="btn soft" href="#/diet/meals/personal">维护餐食</a>
                            <a class="btn ghost" href="#/diet/meals/public">看公共库</a>
                        </div>
                    </div>
                </aside>
            </section>
        `;
        scrollMessagesToBottom();
        // 第三周会话恢复：带着持久化的 sessionId 回来时，拉取历史消息重建对话流
        ensureChatHistory().catch(() => {});
    }
    function renderMessage(message) {
        const mealCards = (message.meals || []).map((meal) => renderMealCard(meal, { feedback: true, sessionId: message.sessionId })).join("");
        const missingSlots = message.missingSlots && message.missingSlots.length
            ? `<div class="chips">${message.missingSlots.map((slot) => `<span class="chip selected">${escapeHtml(SLOT_LABELS[slot] || slot)}</span>`).join("")}</div>`
            : "";
        return `
            <article class="message ${message.role}">
                <div class="bubble">${escapeHtml(message.text)}</div>
                ${missingSlots}
                ${mealCards ? `<div class="grid">${mealCards}</div>` : ""}
            </article>
        `;
    }
    function scrollMessagesToBottom() {
        const messages = document.getElementById("messages");
        if (messages) {
            messages.scrollTop = messages.scrollHeight;
        }
    }
    /** 在消息流尾部插入"阶段进度"占位气泡，后续事件原地更新文案。 */
    function showPendingStage(stageText) {
        const container = document.getElementById("messages");
        if (!container) {
            return;
        }
        const wrapper = document.createElement("article");
        wrapper.className = "message assistant";
        wrapper.innerHTML = `<div class="bubble stage-pending" id="pendingStage">${escapeHtml(stageText)}</div>`;
        container.appendChild(wrapper);
        scrollMessagesToBottom();
    }
    /** 阶段事件回调：把 Trace 事件映射为进度文案，原地更新占位气泡。 */
    function showStageFromEvent(stage) {
        const element = document.getElementById("pendingStage");
        const label = (stage && STAGE_LABELS[stage.eventType]) || "正在处理…";
        if (element) {
            element.textContent = label;
            scrollMessagesToBottom();
        }
    }
    /** 生成请求幂等键：同一轮提交（含 409 重试）复用同一值。 */
    function newRequestId() {
        if (window.crypto && crypto.randomUUID) {
            return crypto.randomUUID();
        }
        return "req-" + Date.now().toString(36) + "-" + Math.random().toString(16).slice(2);
    }
    /**
     * 发送一条消息：优先 SSE 流式（阶段进度），端点不可用（返回 null）时降级普通接口；
     * 409 会话冲突自动重试（复用同一 requestId），失败时输入文案不丢失。
     */
    async function requestChatWithRetry(payload) {
        for (let attempt = 1; ; attempt++) {
            try {
                const streamed = await DietApi.chatStream(payload, { onStage: showStageFromEvent });
                if (streamed) {
                    return streamed;
                }
                // SSE 不可用（代理缓冲/后端未上线）：降级到同步 JSON 接口
                return await DietApi.chat(payload);
            } catch (error) {
                // 409 会话状态冲突（第二周锁瘦身后并发轮次）：稍候自动重试
                if (error.status === 409 && attempt < 3) {
                    await DietApi.sleep(300 * attempt);
                    continue;
                }
                throw error;
            }
        }
    }
    /** 最终回复的打字机动画：长文案逐字呈现，短文案直接展示。 */
    function animateLastAssistantText(text) {
        if (!text || text.length < 40) {
            return;
        }
        const bubbles = document.querySelectorAll("#messages .message.assistant .bubble");
        const bubble = bubbles[bubbles.length - 1];
        if (!bubble) {
            return;
        }
        const total = text.length;
        const step = Math.max(1, Math.ceil(total / 60));
        let index = 0;
        bubble.textContent = "";
        const timer = window.setInterval(() => {
            index = Math.min(total, index + step);
            bubble.textContent = text.slice(0, index);
            scrollMessagesToBottom();
            if (index >= total) {
                window.clearInterval(timer);
            }
        }, 24);
    }
    async function submitChat(form) {
        const messageInput = form.elements.message;
        const message = messageInput.value.trim();
        if (!message || state.chat.sending) {
            return;
        }
        state.chat.messages.push({ role: "user", text: message });
        messageInput.value = "";
        state.chat.sending = true;
        renderChat();
        showPendingStage(STAGE_LABELS.REQUEST_RECEIVED);
        try {
            if (!state.chat.sessionId) {
                const session = await DietApi.createSession();
                state.chat.sessionId = session.sessionId;
                DietApi.setSessionId(state.chat.sessionId);
            }
            // 同一次提交（含 409 重试）复用同一 requestId：服务端按幂等键去重，不重复消费 LLM
            if (!state.chat.pendingRequestId) {
                state.chat.pendingRequestId = newRequestId();
            }
            const payload = {
                sessionId: state.chat.sessionId,
                message,
                sourceMode: state.chat.sourceMode,
                context: {},
                requestId: state.chat.pendingRequestId
            };
            const response = await requestChatWithRetry(payload);
            state.chat.sessionId = response.sessionId || state.chat.sessionId;
            DietApi.setSessionId(state.chat.sessionId);
            state.chat.pendingRequestId = null;
            const fullText = response.clarifyQuestion || response.speechText || "我已经处理完这轮请求。";
            state.chat.lastAssistantText = fullText;
            state.chat.messages.push({
                role: "assistant",
                text: fullText,
                responseType: response.responseType,
                meals: response.displayBlocks || [],
                missingSlots: response.missingSlots || [],
                traceId: response.traceId,
                sessionId: response.sessionId || state.chat.sessionId
            });
        } catch (error) {
            if (error.status === 401) {
                logoutToAuth("登录已过期，请重新登录");
                return;
            }
            showToast(error.message || "聊天请求失败", "error");
            // 失败恢复：撤回本地用户消息、把原文案放回输入框，一键可重发
            state.chat.messages.pop();
            messageInput.value = message;
        } finally {
            state.chat.sending = false;
            renderChat();
            // 渲染完成后对最终回复做打字机呈现
            animateLastAssistantText(state.chat.lastAssistantText);
            state.chat.lastAssistantText = null;
        }
    }
    /** 第三周会话恢复：凭持久化 sessionId 拉取历史消息；越权/不存在的会话清空本地重新开始。 */
    async function ensureChatHistory() {
        if (!state.chat.sessionId || state.chat.historyLoaded || !DietApi.isLoggedIn()) {
            return;
        }
        try {
            const rows = await DietApi.getSessionMessages(state.chat.sessionId, 200);
            if (rows.length) {
                state.chat.messages = rows.map((row) => ({
                    role: row.role === "user" ? "user" : "assistant",
                    text: row.content,
                    sessionId: row.sessionId
                }));
            }
        } catch (error) {
            if (error.status === 401) {
                logoutToAuth("登录已过期，请重新登录");
                return;
            }
            // 会话不存在或不属于当前用户：清空本地记录重新开始
            state.chat.sessionId = null;
            DietApi.setSessionId("");
        } finally {
            state.chat.historyLoaded = true;
        }
        renderChat();
    }
    function resetChat() {
        state.chat.sessionId = null;
        state.chat.historyLoaded = false;
        state.chat.pendingRequestId = null;
        DietApi.setSessionId("");
        state.chat.messages = [
            {
                role: "assistant",
                text: "已开启新会话。告诉我你的用餐时间、口味、场景或健康目标，我来推荐。"
            }
        ];
        renderChat();
    }

    // ================= 个人餐食 =================
    async function renderPersonalMeals() {
        if (!state.slotOptions) {
            app.innerHTML = `<section class="section"><div class="empty">标签字典加载中...</div></section>`;
            await ensureSlotOptions();
            if (currentRoute() !== "/diet/meals/personal") {
                return;
            }
        }
        await ensurePersonalMeals();
        if (currentRoute() !== "/diet/meals/personal") {
            return;
        }
        app.innerHTML = `
            <section class="split">
                <div class="section">
                    <div class="card-title">
                        <div>
                            <h2>个人餐食</h2>
                            <p>维护常吃餐食，聊天推荐时可切换到个人库。</p>
                        </div>
                        <button class="btn primary" data-action="new-meal">新增餐食</button>
                    </div>
                    <div id="personalMealList">${renderMealList(state.personalMeals, { editable: true })}</div>
                </div>
                <aside class="section">
                    ${renderMealForm()}
                </aside>
            </section>
        `;
    }
    function renderMealForm() {
        const meal = state.editingMeal || emptyMeal();
        const title = meal.id ? "编辑餐食" : "新增餐食";
        return `
            <div class="card-title">
                <div>
                    <h3>${title}</h3>
                    <p>从下拉框选择标签，用餐时间为必选项，其余可留空。</p>
                </div>
            </div>
            <form id="mealForm" class="form-grid">
                <input type="hidden" name="mealId" value="${escapeHtml(meal.id || "")}">
                <div class="field full">
                    <label for="mealName">餐食名称</label>
                    <input id="mealName" name="name" value="${escapeHtml(meal.name || "")}" placeholder="例如：番茄鸡蛋面" required>
                </div>
                <p class="field-hint full">标签下拉框支持多选：Windows 按住 Ctrl，Mac 按住 Command 点击可多项选择。</p>
                ${Object.entries(SLOT_LABELS).map(([key, label]) => renderSlotPicker(key, label, meal[key] || [])).join("")}
                <div class="field full">
                    <div class="button-row">
                        <button class="btn primary" type="submit">${meal.id ? "保存修改" : "创建餐食"}</button>
                        <button class="btn ghost" type="button" data-action="cancel-edit">清空</button>
                    </div>
                </div>
            </form>
        `;
    }
    function renderSlotPicker(key, label, selected) {
        const options = state.slotOptions && state.slotOptions[key] ? state.slotOptions[key] : [];
        const selectedSet = new Set(selected || []);
        const required = key === "mealTime";
        return `
            <div class="field">
                <label for="slot-${escapeHtml(key)}">${escapeHtml(label)}${required ? "（必选）" : ""}</label>
                <select
                    id="slot-${escapeHtml(key)}"
                    class="slot-select"
                    name="${escapeHtml(key)}"
                    multiple
                    size="5"
                    ${required ? "required" : ""}
                >
                    ${options.map((option) => {
                        const isSelected = selectedSet.has(option);
                        return `<option value="${escapeHtml(option)}" ${isSelected ? "selected" : ""}>${escapeHtml(option)}</option>`;
                    }).join("")}
                </select>
            </div>
        `;
    }
    function emptyMeal() {
        return {
            name: "",
            mealTime: [],
            mood: [],
            scene: [],
            healthGoal: [],
            cuisine: [],
            taste: [],
            convenience: []
        };
    }
    function renderMealList(meals, options) {
        if (!meals.length) {
            return `<div class="empty">暂无餐食。可以先新增几道常吃的菜。</div>`;
        }
        return `<div class="grid two">${meals.map((meal) => renderMealCard(meal, options || {})).join("")}</div>`;
    }
    function renderMealCard(meal, options) {
        const editable = options && options.editable;
        const editablePublic = options && options.editablePublic;
        const feedback = options && options.feedback;
        const publicLibrary = options && options.publicLibrary;
        // "已添加"统一判断：本会话刚加入过，或个人库中已有同名菜品
        const added = meal._added || isMealInPersonal(meal);
        const addedBadge = added && (feedback || publicLibrary) ? `<span class="added-badge">已添加</span>` : "";
        return `
            <article class="meal-card">
                <header>
                    <div>
                        <h3>${escapeHtml(meal.name)}</h3>
                        <p class="muted">${escapeHtml(meal.sourceType || "")}</p>
                    </div>
                    <div class="card-header-side">
                        ${addedBadge}
                        ${meal.matchScore ? `<span class="score">匹配 ${Math.round(meal.matchScore * 100)}%</span>` : ""}
                    </div>
                </header>
                <div class="chips">${mealTags(meal).map((tag) => `<span class="chip selected">${escapeHtml(tag)}</span>`).join("")}</div>
                ${editable ? `
                    <div class="button-row">
                        <button class="btn soft" data-action="edit-meal" data-id="${escapeHtml(meal.id)}">编辑</button>
                        <button class="btn ghost" data-action="delete-meal" data-id="${escapeHtml(meal.id)}">删除</button>
                    </div>
                ` : ""}
                ${editablePublic ? `
                    <div class="button-row">
                        <button class="btn soft" data-action="edit-public-meal" data-id="${escapeHtml(meal.id)}">编辑</button>
                        <button class="btn ghost" data-action="delete-public-meal" data-id="${escapeHtml(meal.id)}">删除</button>
                    </div>
                ` : ""}
                ${feedback ? renderFeedbackBubbles(meal, options) : ""}
                ${publicLibrary ? renderLibraryAddControl(meal) : ""}
            </article>
        `;
    }
    /** 满意/不满意图标气泡：点击后锁定为已选态（防重复反馈流水）。 */
    function renderFeedbackBubbles(meal, options) {
        const locked = Boolean(meal._feedback);
        const bubble = (value, icon, label) => {
            const selected = meal._feedback === value;
            const stateClass = selected ? (value === "LIKE" ? "selected like" : "selected dislike") : "";
            return `
                <button
                    class="chip icon-bubble ${stateClass}"
                    data-action="feedback-icon"
                    data-value="${value}"
                    data-item-id="${escapeHtml(meal.id)}"
                    data-session-id="${escapeHtml(options.sessionId || "")}"
                    ${locked ? "disabled" : ""}
                    title="${label}"
                >${icon} ${label}</button>
            `;
        };
        return `
            <div class="feedback-bubbles">
                ${bubble("LIKE", "👍", "满意")}
                ${bubble("DISLIKE", "👎", "不满意")}
            </div>
            ${renderAddPersonalPrompt(meal)}
        `;
    }
    /** 公共库菜品点满意后的入库询问气泡：每卡最多出现一次，加入成功后永久消失。 */
    function renderAddPersonalPrompt(meal) {
        if (meal._added || meal._addPrompted || meal._feedback !== "LIKE" || meal.sourceType !== "PUBLIC") {
            return "";
        }
        return `
            <div class="add-prompt">
                <span>是否将「${escapeHtml(meal.name)}」加入你的个人餐食库？加入后切换到个人库模式会优先推荐它。</span>
                <div class="button-row">
                    <button class="btn primary" data-action="confirm-add-personal" data-meal-id="${escapeHtml(meal.id)}">加入</button>
                    <button class="btn ghost" data-action="dismiss-add-personal" data-meal-id="${escapeHtml(meal.id)}">暂不</button>
                </div>
            </div>
        `;
    }
    function mealTags(meal) {
        return Object.keys(SLOT_LABELS).flatMap((key) => (meal[key] || []).map((value) => `${SLOT_LABELS[key]}：${value}`));
    }
    async function ensurePersonalMeals(force) {
        if (!force && state.personalMeals.length) {
            return;
        }
        try {
            state.personalMeals = await DietApi.listPersonalMeals();
            if (currentRoute() === "/diet/meals/personal") {
                document.getElementById("personalMealList").innerHTML = renderMealList(state.personalMeals, { editable: true });
            }
        } catch (error) {
            showErrorOrAuth(error, "个人餐食加载失败");
        }
    }
    async function ensureSlotOptions() {
        if (state.slotOptions) {
            return;
        }
        try {
            state.slotOptions = await DietApi.slotOptions();
        } catch (error) {
            showToast(error.message || "槽位字典加载失败", "error");
            throw error;
        }
    }
    async function saveMeal(form) {
        const { id, payload } = mealPayloadFromForm(form);
        if (!payload.name) {
            showToast("请填写餐食名称", "error");
            return;
        }
        if (!payload.mealTime.length) {
            showToast("请至少选择一个用餐时间标签", "error");
            return;
        }
        const restore = setLoading(form.querySelector("button[type=submit]"), "保存中...");
        try {
            await guard(async () => {
                if (id) {
                    return DietApi.updatePersonalMeal(id, payload);
                }
                return DietApi.createPersonalMeal(payload);
            }, id ? "餐食已更新" : "餐食已创建");
            state.editingMeal = null;
            await ensurePersonalMeals(true);
            renderPersonalMeals();
        } finally {
            restore();
        }
    }
    function mealPayloadFromForm(form) {
        const formData = new FormData(form);
        const payload = {
            name: String(formData.get("name") || "").trim()
        };
        Object.keys(SLOT_LABELS).forEach((key) => {
            payload[key] = formData.getAll(key).filter(Boolean);
        });
        return {
            id: String(formData.get("mealId") || "").trim(),
            payload
        };
    }
    function editMeal(id) {
        const meal = state.personalMeals.find((item) => String(item.id) === String(id));
        if (!meal) {
            showToast("没有找到要编辑的餐食", "error");
            return;
        }
        state.editingMeal = JSON.parse(JSON.stringify(meal));
        renderPersonalMeals();
    }
    async function deleteMeal(id) {
        const meal = state.personalMeals.find((item) => String(item.id) === String(id));
        if (!meal || !window.confirm(`确定删除“${meal.name}”？`)) {
            return;
        }
        await guard(async () => {
            await DietApi.deletePersonalMeal(id);
            await ensurePersonalMeals(true);
            renderPersonalMeals();
        }, "餐食已删除");
    }

    // ================= 公共餐食库管理（管理员） =================
    function editPublicMeal(id) {
        const meal = state.publicMeals.find((item) => String(item.id) === String(id));
        if (!meal) {
            showToast("没有找到要编辑的公共餐食", "error");
            return;
        }
        state.editingPublicMeal = JSON.parse(JSON.stringify(meal));
        renderPublicMeals();
    }

    async function savePublicMeal(form) {
        const { id, payload } = mealPayloadFromForm(form);
        if (!payload.name) {
            showToast("请填写餐食名称", "error");
            return;
        }
        if (!payload.mealTime.length) {
            showToast("请至少选择一个用餐时间标签", "error");
            return;
        }
        const restore = setLoading(form.querySelector("button[type=submit]"), "保存中...");
        try {
            await guard(async () => {
                if (id) {
                    return DietApi.updatePublicMeal(id, payload);
                }
                return DietApi.createPublicMeal(payload);
            }, id ? "公共餐食已更新" : "公共餐食已创建");
            state.editingPublicMeal = null;
            await ensurePublicMeals(true);
            renderPublicMeals();
        } finally {
            restore();
        }
    }

    async function deletePublicMeal(id) {
        const meal = state.publicMeals.find((item) => String(item.id) === String(id));
        if (!meal || !window.confirm(`确定删除公共餐食「${meal.name}」？所有用户将不再看到它。`)) {
            return;
        }
        await guard(async () => {
            await DietApi.deletePublicMeal(id);
            await ensurePublicMeals(true);
            renderPublicMeals();
        }, "公共餐食已删除");
    }

    // ================= 公共餐食（用户只读+加入；管理员管理） =================
    async function renderPublicMeals() {
        if (isAdmin()) {
            // 管理员：公共库管理台（列表 + 编辑表单）
            if (!state.slotOptions) {
                app.innerHTML = `<section class="section"><div class="empty">标签字典加载中...</div></section>`;
                await ensureSlotOptions();
                if (currentRoute() !== "/diet/meals/public") {
                    return;
                }
            }
            await ensurePublicMeals();
            if (currentRoute() !== "/diet/meals/public") {
                return;
            }
            const meal = state.editingPublicMeal || emptyMeal();
            app.innerHTML = `
                <section class="split">
                    <div class="section">
                        <div class="card-title">
                            <div>
                                <h2>公共餐食库管理</h2>
                                <p>维护所有用户可见的公共菜单，修改后立即生效。</p>
                            </div>
                            <button class="btn primary" data-action="new-public-meal">新增公共餐食</button>
                        </div>
                        <div id="publicMealList">${renderMealList(state.publicMeals, { editablePublic: true })}</div>
                    </div>
                    <aside class="section">
                        <div class="card-title">
                            <div>
                                <h3>${meal.id ? "编辑公共餐食" : "新增公共餐食"}</h3>
                                <p>从下拉框选择标签，用餐时间为必选项，其余可留空。</p>
                            </div>
                        </div>
                        <form id="publicMealForm" class="form-grid">
                            <input type="hidden" name="mealId" value="${escapeHtml(meal.id || "")}">
                            <div class="field full">
                                <label for="publicMealName">餐食名称</label>
                                <input id="publicMealName" name="name" value="${escapeHtml(meal.name || "")}" placeholder="例如：番茄鸡蛋面" required>
                            </div>
                            <p class="field-hint full">标签下拉框支持多选：Windows 按住 Ctrl，Mac 按住 Command 点击可多项选择。</p>
                            ${Object.entries(SLOT_LABELS).map(([key, label]) => renderSlotPicker(key, label, meal[key] || [])).join("")}
                            <div class="field full">
                                <div class="button-row">
                                    <button class="btn primary" type="submit">${meal.id ? "保存修改" : "创建餐食"}</button>
                                    <button class="btn ghost" type="button" data-action="cancel-edit-public">清空</button>
                                </div>
                            </div>
                        </form>
                    </aside>
                </section>
            `;
            return;
        }
        // 用户：只读浏览 + 加入个人库动线
        app.innerHTML = `
            <section class="section">
                <div class="card-title">
                    <div>
                        <h2>公共餐食</h2>
                        <p>看到喜欢的菜，点「加入个人库」保存下来，以后会优先推荐给你。</p>
                    </div>
                    <a class="btn primary" href="#/diet/chat">去聊天推荐</a>
                </div>
                <div id="publicMealList">${renderMealList(state.publicMeals, { publicLibrary: true })}</div>
            </section>
        `;
        await ensurePublicMeals();
        // "已添加"徽标需要个人库名单做同名比对
        await ensurePersonalMeals();
        if (currentRoute() === "/diet/meals/public") {
            document.getElementById("publicMealList").innerHTML = renderMealList(state.publicMeals, { publicLibrary: true });
        }
    }
    async function ensurePublicMeals(force) {
        if (!force && state.publicMeals.length) {
            return;
        }
        try {
            state.publicMeals = await DietApi.listPublicMeals();
            if (currentRoute() === "/diet/meals/public") {
                // 管理员是编辑列表，用户是只读列表（带加入动线）
                const options = isAdmin() ? { editablePublic: true } : { publicLibrary: true };
                document.getElementById("publicMealList").innerHTML = renderMealList(state.publicMeals, options);
            }
        } catch (error) {
            showErrorOrAuth(error, "公共餐食加载失败");
        }
    }

    // ================= Trace 调试 =================
    function renderTraces() {
        const selected = state.traces.selected;
        app.innerHTML = `
            <section class="split">
                <div class="section">
                    <div class="card-title">
                        <div>
                            <h2>Trace 调试</h2>
                            <p>按时间范围或会话查询请求链路，查看意图修正、槽位和推荐事件。</p>
                        </div>
                    </div>
                    <form id="traceFilterForm" class="form-grid">
                        <div class="field">
                            <label>开始时间</label>
                            <input type="datetime-local" name="startAt" value="${escapeHtml(state.traces.filters.startAt)}" required>
                        </div>
                        <div class="field">
                            <label>结束时间</label>
                            <input type="datetime-local" name="endAt" value="${escapeHtml(state.traces.filters.endAt)}" required>
                        </div>
                        <div class="field">
                            <label>会话 ID（可选）</label>
                            <input name="sessionId" value="${escapeHtml(state.traces.filters.sessionId)}" placeholder="填写后按会话查询">
                        </div>
                        <div class="field">
                            <label>数量上限</label>
                            <input type="number" min="1" max="500" name="limit" value="${escapeHtml(state.traces.filters.limit)}">
                        </div>
                        <div class="field">
                            <label>标注状态</label>
                            <select name="onlyUnlabeled">
                                <option value="false" ${!state.traces.filters.onlyUnlabeled ? "selected" : ""}>全部</option>
                                <option value="true" ${state.traces.filters.onlyUnlabeled ? "selected" : ""}>仅未标注</option>
                            </select>
                        </div>
                        <div class="field">
                            <span>&nbsp;</span>
                            <button class="btn primary" type="submit">${state.traces.loading ? "查询中..." : "查询 Trace"}</button>
                        </div>
                    </form>
                    <div class="subtle-divider"></div>
                    ${renderTraceTable()}
                </div>
                <aside class="section">
                    ${selected ? renderTraceDetail(selected) : `<div class="empty">选择一条 Trace 查看详情和标注表单。</div>`}
                </aside>
            </section>
        `;
    }
    function renderTraceTable() {
        if (!state.traces.rows.length) {
            return `<div class="empty">暂无 Trace 数据。可以先在聊天页发起几轮对话。</div>`;
        }
        return `
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>Trace ID</th>
                            <th>会话</th>
                            <th>状态</th>
                            <th>事件</th>
                            <th>耗时</th>
                            <th>创建时间</th>
                            <th>标注</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${state.traces.rows.map((row) => `
                            <tr>
                                <td>${escapeHtml(row.traceId)}</td>
                                <td>${escapeHtml(row.sessionId)}</td>
                                <td>${escapeHtml(row.status || "-")}</td>
                                <td>${escapeHtml(row.eventCount ?? "-")}</td>
                                <td>${row.durationMs ? `${escapeHtml(row.durationMs)} ms` : "-"}</td>
                                <td>${escapeHtml(row.createdAt || "-")}</td>
                                <td>${row.expectedIntent ? `<span class="badge">${escapeHtml(row.expectedIntent)}</span>` : "<span class=\"muted\">未标注</span>"}</td>
                                <td><button class="btn soft" data-action="select-trace" data-trace-id="${escapeHtml(row.traceId)}">查看</button></td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        `;
    }
    function renderTraceDetail(trace) {
        return `
            <div class="card-title">
                <div>
                    <h3>Trace 详情</h3>
                    <p>${escapeHtml(trace.traceId)}</p>
                </div>
            </div>
            <div class="grid">
                <div>
                    <span class="badge">${escapeHtml(trace.status || "UNKNOWN")}</span>
                    <p class="muted">Session：${escapeHtml(trace.sessionId || "-")} · Events：${escapeHtml(trace.eventCount ?? "-")} · Duration：${escapeHtml(trace.durationMs ?? "-")} ms</p>
                </div>
                <details open>
                    <summary>Trace JSON</summary>
                    <pre class="json-box">${escapeHtml(safeJson(trace.traceJson))}</pre>
                </details>
                <form id="traceLabelForm" class="form-grid">
                    <input type="hidden" name="traceId" value="${escapeHtml(trace.traceId)}">
                    <div class="field">
                        <label>预期意图</label>
                        <select name="expectedIntent">
                            <option value="">不标注</option>
                            ${INTENTS.map((intent) => `<option value="${intent}" ${trace.expectedIntent === intent ? "selected" : ""}>${intent}</option>`).join("")}
                        </select>
                    </div>
                    <div class="field">
                        <label>澄清动作</label>
                        <select name="expectedClarifyAction">
                            <option value="">不标注</option>
                            <option value="ASK" ${trace.expectedClarifyAction === "ASK" ? "selected" : ""}>ASK</option>
                            <option value="READY" ${trace.expectedClarifyAction === "READY" ? "selected" : ""}>READY</option>
                        </select>
                    </div>
                    <div class="field full">
                        <label>预期槽位 JSON</label>
                        <textarea name="expectedSlots" placeholder='{"mealTime":["晚餐"],"taste":["清淡"]}'>${escapeHtml(safeJson(trace.expectedSlots))}</textarea>
                    </div>
                    <div class="field full">
                        <label>备注</label>
                        <textarea name="labelNote" placeholder="标注说明">${escapeHtml(trace.labelNote || "")}</textarea>
                    </div>
                    <div class="field full">
                        <button class="btn primary" type="submit">保存标注</button>
                    </div>
                </form>
            </div>
        `;
    }
    async function searchTraces(form) {
        const formData = new FormData(form);
        state.traces.filters = {
            startAt: formData.get("startAt"),
            endAt: formData.get("endAt"),
            sessionId: formData.get("sessionId").trim(),
            onlyUnlabeled: formData.get("onlyUnlabeled") === "true",
            limit: Number(formData.get("limit") || 50)
        };
        state.traces.loading = true;
        renderTraces();
        try {
            if (state.traces.filters.sessionId) {
                state.traces.rows = await DietApi.listSessionTraces(state.traces.filters.sessionId, state.traces.filters.limit);
            } else {
                state.traces.rows = await DietApi.listTraces({
                    startAt: state.traces.filters.startAt,
                    endAt: state.traces.filters.endAt,
                    onlyUnlabeled: state.traces.filters.onlyUnlabeled,
                    limit: state.traces.filters.limit
                });
            }
            state.traces.selected = state.traces.rows[0] || null;
        } catch (error) {
            showErrorOrAuth(error, "Trace 查询失败");
        } finally {
            state.traces.loading = false;
            renderTraces();
        }
    }
    async function selectTrace(traceId) {
        await guard(async () => {
            state.traces.selected = await DietApi.getTrace(traceId);
            renderTraces();
        });
    }
    async function saveTraceLabel(form) {
        const formData = new FormData(form);
        const traceId = formData.get("traceId");
        const slotsText = formData.get("expectedSlots").trim();
        let expectedSlots = null;
        if (slotsText) {
            try {
                expectedSlots = JSON.parse(slotsText);
            } catch (error) {
                showToast("预期槽位必须是合法 JSON", "error");
                return;
            }
        }
        const payload = {
            expectedIntent: formData.get("expectedIntent") || null,
            expectedSlots,
            expectedClarifyAction: formData.get("expectedClarifyAction") || null,
            labelNote: formData.get("labelNote").trim()
        };
        await guard(async () => {
            await DietApi.labelTrace(traceId, payload);
            state.traces.selected = await DietApi.getTrace(traceId);
            const index = state.traces.rows.findIndex((row) => row.traceId === traceId);
            if (index >= 0) {
                state.traces.rows[index] = state.traces.selected;
            }
            renderTraces();
        }, "Trace 标注已保存");
    }

    // ================= 评估后台（第四周异步任务轮询） =================
    function renderEvaluations() {
        app.innerHTML = `
            <section class="section">
                <div class="card-title">
                    <div>
                        <h2>评估报告</h2>
                        <p>基于已落库 Trace 生成规则评分、可选 LLM Judge 和反馈归因指标。大批量评估自动转后台任务。</p>
                    </div>
                </div>
                <form id="evaluationForm" class="form-grid">
                    <div class="field">
                        <label>开始时间</label>
                        <input type="datetime-local" name="startAt" value="${escapeHtml(state.evaluation.form.startAt)}" required>
                    </div>
                    <div class="field">
                        <label>结束时间</label>
                        <input type="datetime-local" name="endAt" value="${escapeHtml(state.evaluation.form.endAt)}" required>
                    </div>
                    <div class="field">
                        <label>数量上限</label>
                        <input type="number" min="1" max="10000" name="limit" value="${escapeHtml(state.evaluation.form.limit)}">
                    </div>
                    <div class="field">
                        <label>LLM Judge</label>
                        <select name="includeLlmJudge">
                            <option value="false" ${!state.evaluation.form.includeLlmJudge ? "selected" : ""}>关闭</option>
                            <option value="true" ${state.evaluation.form.includeLlmJudge ? "selected" : ""}>开启</option>
                        </select>
                    </div>
                    <div class="field full">
                        <button class="btn primary" type="submit">${state.evaluation.loading ? "评估中..." : "生成评估报告"}</button>
                    </div>
                </form>
            </section>
            <section class="section" style="margin-top: 18px;">
                ${renderEvaluationReport()}
            </section>
        `;
    }
    function renderEvaluationReport() {
        const report = state.evaluation.report;
        if (!report) {
            return `<div class="empty">暂无报告。选择时间范围后生成评估。</div>`;
        }
        return `
            <div class="grid three">
                ${statCard("Trace 总数", report.totalTraces, "本次纳入评估的请求数")}
                ${statCard("已标注", report.labeledTraces, "有人工标签的 Trace 数")}
                ${statCard("平均分", report.avgScore === null || report.avgScore === undefined ? "-" : Number(report.avgScore).toFixed(2), "综合评分")}
            </div>
            <div class="subtle-divider"></div>
            <div class="grid two">
                <div>
                    <h3>指标均值</h3>
                    ${renderMetrics(report.metricAverages)}
                </div>
                <div>
                    <h3>报告范围</h3>
                    <p class="muted">${escapeHtml(report.startAt)} 至 ${escapeHtml(report.endAt)}</p>
                </div>
            </div>
            <div class="subtle-divider"></div>
            ${renderEvaluationTable(report.traceResults || [])}
        `;
    }
    function renderMetrics(metrics) {
        const entries = Object.entries(metrics || {});
        if (!entries.length) {
            return `<div class="empty">暂无指标</div>`;
        }
        return `<div class="chips">${entries.map(([key, value]) => `<span class="chip selected">${escapeHtml(key)}：${Number(value).toFixed(2)}</span>`).join("")}</div>`;
    }
    function renderEvaluationTable(rows) {
        if (!rows.length) {
            return `<div class="empty">暂无 Trace 明细</div>`;
        }
        return `
            <div class="table-wrap">
                <table>
                    <thead>
                        <tr>
                            <th>Trace ID</th>
                            <th>会话</th>
                            <th>综合分</th>
                            <th>规则分</th>
                            <th>LLM 分</th>
                            <th>反馈分</th>
                            <th>指标 / 明细</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${rows.map((row) => `
                            <tr>
                                <td>${escapeHtml(row.traceId)}</td>
                                <td>${escapeHtml(row.sessionId)}</td>
                                <td>${formatScore(row.score)}</td>
                                <td>${formatScore(row.ruleScore)}</td>
                                <td>${formatScore(row.llmJudgeScore)}</td>
                                <td>${formatScore(row.userFeedbackScore)}</td>
                                <td>
                                    <details>
                                        <summary>查看 JSON</summary>
                                        <pre class="json-box">${escapeHtml(JSON.stringify({ metrics: row.metrics, detail: row.detail }, null, 2))}</pre>
                                    </details>
                                </td>
                            </tr>
                        `).join("")}
                    </tbody>
                </table>
            </div>
        `;
    }
    function formatScore(value) {
        return value === null || value === undefined ? "-" : Number(value).toFixed(2);
    }
    async function runEvaluation(form) {
        const formData = new FormData(form);
        state.evaluation.form = {
            startAt: formData.get("startAt"),
            endAt: formData.get("endAt"),
            limit: Number(formData.get("limit") || 50),
            includeLlmJudge: formData.get("includeLlmJudge") === "true"
        };
        state.evaluation.loading = true;
        renderEvaluations();
        const button = form.querySelector("button[type=submit]");
        try {
            const result = await DietApi.evaluate(state.evaluation.form);
            if (result && result.jobId) {
                // 大批量评估转异步任务（第四周）：轮询进度直到 DONE/FAILED
                for (let i = 0; i < 600; i++) {
                    const job = await DietApi.getEvaluationJob(result.jobId);
                    if (job.status === "DONE") {
                        state.evaluation.report = job.report;
                        break;
                    }
                    if (job.status === "FAILED") {
                        throw new Error(job.errorMessage || "评估任务失败");
                    }
                    if (button) {
                        button.textContent = `评估中... ${job.processed ?? 0}/${job.total ?? "?"}`;
                    }
                    await DietApi.sleep(2000);
                }
            } else {
                state.evaluation.report = result;
            }
        } catch (error) {
            showErrorOrAuth(error, "评估失败");
        } finally {
            state.evaluation.loading = false;
            renderEvaluations();
        }
    }

    // ================= 卡片反馈与入库（满意 → 询问加入个人库） =================
    /** 按 itemId 在消息卡片列表中定位 meal 对象（反馈/入库标记都挂在这个对象上）。 */
    function findMealById(itemId) {
        const target = String(itemId);
        for (const message of state.chat.messages) {
            const meals = message.meals || [];
            const meal = meals.find((item) => String(item.id) === target);
            if (meal) {
                return meal;
            }
        }
        return null;
    }
    /** 满意/不满意图标气泡点击：记录反馈并锁定该卡；公共库菜品点满意后弹出"是否加入个人库"询问气泡。 */
    async function handleFeedbackIcon(button) {
        const meal = findMealById(button.dataset.itemId);
        if (!meal || meal._feedback) {
            return; // 已反馈过的卡片直接忽略（按钮层已禁用，这里是兜底）
        }
        const action = button.dataset.value === "DISLIKE" ? "DISLIKE" : "LIKE";
        // 乐观置位并重渲染：按钮立即锁定，公共库满意卡同步出现询问气泡
        meal._feedback = action;
        renderChat();
        try {
            await guard(async () => {
                await DietApi.saveFeedback({
                    sessionId: button.dataset.sessionId || state.chat.sessionId,
                    itemId: Number(meal.id),
                    action,
                    rating: action === "DISLIKE" ? 2 : 5,
                    reason: ""
                });
            }, "反馈已记录");
        } catch (ignored) {
            // 失败提示已由 guard 弹出（含 401 切登录）；选中态保留，避免交互反复
        }
        if (action === "LIKE" && meal.sourceType === "PUBLIC" && !(meal.mealTime || []).length) {
            // 后端创建个人餐食要求餐次必填：缺餐次的公共菜品无法一键入库，直接跳过询问
            meal._addPrompted = true;
            showToast("该菜品缺少用餐时间标签，暂不能一键加入个人库", "error");
            renderChat();
        }
    }
    /** 该菜品是否已在个人餐食库中（按名称匹配，用于公共库/对话卡片的"已添加"徽标）。 */
    function isMealInPersonal(meal) {
        return Boolean(meal) && state.personalMeals.some((item) => item.name === meal.name);
    }

    /** 公共库页卡片的加入控件：未添加 → 「加入个人库」按钮 → 卡片内确认气泡；已添加/已拒绝 → 不再渲染。 */
    function renderLibraryAddControl(meal) {
        if (meal._added || isMealInPersonal(meal) || meal._addPrompted) {
            return "";
        }
        if (meal._confirming) {
            return `
                <div class="add-prompt">
                    <span>将「${escapeHtml(meal.name)}」加入你的个人餐食库？加入后会优先推荐给你。</span>
                    <div class="button-row">
                        <button class="btn primary" data-action="confirm-add-library" data-meal-id="${escapeHtml(meal.id)}">加入</button>
                        <button class="btn ghost" data-action="cancel-add-library" data-meal-id="${escapeHtml(meal.id)}">暂不</button>
                    </div>
                </div>
            `;
        }
        return `
            <div class="feedback-bubbles">
                <button class="chip icon-bubble" data-action="add-to-library" data-meal-id="${escapeHtml(meal.id)}">➕ 加入个人库</button>
            </div>
        `;
    }

    /** 统一入库逻辑（对话卡片与公共库页共用）：餐次校验 → 同名查重 → 带标签复制进个人库。 */
    async function addToPersonal(meal) {
        if (!meal || meal._added) {
            return;
        }
        // 后端创建个人餐食要求餐次必填
        if (!(meal.mealTime || []).length) {
            showToast("该菜品缺少用餐时间标签，暂不能加入个人库", "error");
            meal._addPrompted = true;
            return;
        }
        try {
            // 重名防护：meal_item 无唯一约束，入库前先按名称查重
            await ensurePersonalMeals(true);
            if (isMealInPersonal(meal)) {
                meal._added = true;
                showToast("该菜品已在个人库中");
                return;
            }
            await guard(async () => {
                await DietApi.createPersonalMeal({
                    name: meal.name,
                    mealTime: meal.mealTime || [],
                    mood: meal.mood || [],
                    scene: meal.scene || [],
                    healthGoal: meal.healthGoal || [],
                    cuisine: meal.cuisine || [],
                    taste: meal.taste || [],
                    convenience: meal.convenience || []
                });
            }, "已加入个人餐食库");
            meal._added = true;
        } catch (ignored) {
            // 失败提示已由 guard/showErrorOrAuth 弹出；_added 未置位，可重试
        }
    }

    /** 对话卡片询问气泡选「加入」：复用统一入库逻辑后刷新对话。 */
    async function confirmAddPersonal(mealId) {
        const meal = findMealById(mealId);
        if (!meal || meal._added) {
            return;
        }
        await addToPersonal(meal);
        renderChat();
    }

    /** 公共库页确认气泡选「加入」：入库后刷新公共库列表（徽标变"已添加"）。 */
    async function confirmAddLibrary(mealId) {
        const meal = state.publicMeals.find((item) => String(item.id) === String(mealId));
        if (!meal || meal._added) {
            return;
        }
        await addToPersonal(meal);
        if (!meal._added) {
            meal._addPrompted = true;   // 加入失败（如重名提示）后不再反复弹确认
        }
        renderPublicMeals();
    }

    /** 公共库页确认气泡选「暂不」：该卡不再弹出确认。 */
    function cancelAddLibrary(mealId) {
        const meal = state.publicMeals.find((item) => String(item.id) === String(mealId));
        if (!meal) {
            return;
        }
        meal._confirming = false;
        meal._addPrompted = true;
        renderPublicMeals();
    }
    /** 询问气泡选「暂不」：该卡不再询问。 */
    function dismissAddPersonal(mealId) {
        const meal = findMealById(mealId);
        if (!meal) {
            return;
        }
        meal._addPrompted = true;
        renderChat();
    }

    // ================= 事件分发 =================
    function handleClick(event) {
        const target = event.target.closest("[data-action]");
        if (!target) {
            return;
        }
        const action = target.dataset.action;
        if (action === "toggle-auth-mode") {
            state.auth.mode = state.auth.mode === "register" ? "login" : "register";
            renderAuth();
        } else if (action === "logout") {
            logoutToAuth("已退出登录");
        } else if (action === "set-source") {
            state.chat.sourceMode = target.dataset.source;
            resetChat();
        } else if (action === "new-session") {
            resetChat();
        } else if (action === "quick-message") {
            const input = document.querySelector("#chatForm textarea[name=message]");
            if (input) {
                input.value = target.dataset.message;
                input.focus();
            }
        } else if (action === "feedback-icon") {
            handleFeedbackIcon(target);
        } else if (action === "confirm-add-personal") {
            confirmAddPersonal(target.dataset.mealId);
        } else if (action === "dismiss-add-personal") {
            dismissAddPersonal(target.dataset.mealId);
        } else if (action === "add-to-library") {
            const meal = state.publicMeals.find((item) => String(item.id) === String(target.dataset.mealId));
            if (meal) {
                meal._confirming = true;
                renderPublicMeals();
            }
        } else if (action === "confirm-add-library") {
            confirmAddLibrary(target.dataset.mealId);
        } else if (action === "cancel-add-library") {
            cancelAddLibrary(target.dataset.mealId);
        } else if (action === "new-public-meal") {
            state.editingPublicMeal = emptyMeal();
            renderPublicMeals();
        } else if (action === "edit-public-meal") {
            editPublicMeal(target.dataset.id);
        } else if (action === "delete-public-meal") {
            deletePublicMeal(target.dataset.id);
        } else if (action === "cancel-edit-public") {
            state.editingPublicMeal = null;
            renderPublicMeals();
        } else if (action === "new-meal") {
            state.editingMeal = emptyMeal();
            renderPersonalMeals();
        } else if (action === "edit-meal") {
            editMeal(target.dataset.id);
        } else if (action === "delete-meal") {
            deleteMeal(target.dataset.id);
        } else if (action === "cancel-edit") {
            state.editingMeal = null;
            renderPersonalMeals();
        } else if (action === "select-trace") {
            selectTrace(target.dataset.traceId);
        } else if (action === "open-trace") {
            state.traces.filters.sessionId = "";
            navigate("/admin/traces");
            selectTrace(target.dataset.traceId);
        }
    }
    function handleSubmit(event) {
        const form = event.target;
        if (form.id === "authForm") {
            event.preventDefault();
            submitAuth(form);
        } else if (form.id === "chatForm") {
            event.preventDefault();
            submitChat(form);
        } else if (form.id === "mealForm") {
            event.preventDefault();
            if (!form.checkValidity()) {
                form.reportValidity();
                return;
            }
            saveMeal(form);
        } else if (form.id === "publicMealForm") {
            event.preventDefault();
            if (!form.checkValidity()) {
                form.reportValidity();
                return;
            }
            savePublicMeal(form);
        } else if (form.id === "traceFilterForm") {
            event.preventDefault();
            searchTraces(form);
        } else if (form.id === "traceLabelForm") {
            event.preventDefault();
            saveTraceLabel(form);
        } else if (form.id === "evaluationForm") {
            event.preventDefault();
            runEvaluation(form);
        }
    }

    // ================= 启动 =================
    window.addEventListener("hashchange", render);
    app.addEventListener("click", handleClick);
    app.addEventListener("submit", handleSubmit);
    // 角色化入口：未登录先渲染登录视图；用户落地对话界面，管理员落地公共库管理
    if (!DietApi.isLoggedIn()) {
        renderAuth();
    } else {
        initUserBar();
        if (!location.hash) {
            navigate(defaultRoute());
        } else {
            render();
        }
    }
})();
