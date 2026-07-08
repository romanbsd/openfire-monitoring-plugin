import { config } from './config.js';
import { sleep, waitFor } from './wait.js';

export class AdminClient {
  #cookieJar = new Map();

  async #request(url, { method = 'GET', body, headers = {} } = {}) {
    const cookieHeader = [...this.#cookieJar.entries()].map(([k, v]) => `${k}=${v}`).join('; ');
    const res = await fetch(url, {
      method,
      headers: {
        ...(cookieHeader ? { Cookie: cookieHeader } : {}),
        ...headers,
      },
      body,
      redirect: 'manual',
    });
    const setCookie = res.headers.getSetCookie?.() ?? [];
    for (const raw of setCookie) {
      const [pair] = raw.split(';');
      const [name, value] = pair.split('=');
      if (name && value !== undefined) {
        this.#cookieJar.set(name, value);
      }
    }
    return res;
  }

  #csrfFromJar() {
    return this.#cookieJar.get('csrf') ?? '';
  }

  async #csrfFromPage(path) {
    const res = await this.#request(`${config.adminUrl}${path}`);
    const html = await res.text();
    const match = html.match(/name="csrf" value="([^"]+)"/);
    return match?.[1] ?? this.#csrfFromJar();
  }

  async login() {
    const csrf = await this.#csrfFromPage('/login.jsp');
    const body = new URLSearchParams({
      username: config.adminUser,
      password: config.adminPass,
      login: 'true',
      csrf,
    });
    await this.#request(`${config.adminUrl}/login.jsp`, { method: 'POST', body });

    const index = await this.#request(`${config.adminUrl}/index.jsp`);
    const html = await index.text();
    if (!html.toLowerCase().includes('logout')) {
      throw new Error('Admin login failed');
    }
  }

  async ensureLoggedIn() {
    await this.login();
  }

  async userExists(username) {
    await this.ensureLoggedIn();
    const res = await this.#request(
      `${config.adminUrl}/user-summary.jsp?search=${encodeURIComponent(username)}&searchType=Username`,
    );
    const html = await res.text();
    return html.includes(`>${username}<`);
  }

  async createUser(username, password) {
    if (await this.userExists(username)) {
      return;
    }
    await this.ensureLoggedIn();
    const csrf = await this.#csrfFromPage('/user-create.jsp');
    const body = new URLSearchParams({
      csrf,
      username,
      name: username,
      email: `${username}@localhost`,
      password,
      passwordConfirm: password,
      create: 'Create User',
    });
    await this.#request(`${config.adminUrl}/user-create.jsp`, { method: 'POST', body });
    if (!(await this.userExists(username))) {
      throw new Error(`Failed to create user ${username}`);
    }
  }

  async buildProgress() {
    await this.ensureLoggedIn();
    const res = await this.#request(`${config.adminUrl}/plugins/monitoring/api/buildprogress`);
    const text = (await res.text()).trim().replaceAll('"', '');
    if (text === '-1') return -1;
    const n = Number(text);
    return Number.isFinite(n) ? n : -1;
  }

  async waitForIndexIdle(timeoutMs = 30_000) {
    let idleSince = -1;
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const progress = await this.buildProgress();
      if (progress >= 0 && progress < 100) {
        idleSince = -1;
      } else if (progress === -1) {
        if (idleSince < 0) {
          idleSince = Date.now();
        } else if (Date.now() - idleSince >= 3_000) {
          return;
        }
      }
      await sleep(2_000);
    }
  }

  async rebuildIndexes() {
    await this.ensureLoggedIn();
    await this.waitForIndexIdle();
    const csrf = await this.#csrfFromPage('/plugins/monitoring/archiving-settings.jsp');
    const body = new URLSearchParams({
      csrf,
      rebuild: 'Rebuild Index',
    });
    const res = await this.#request(`${config.adminUrl}/plugins/monitoring/archiving-settings.jsp`, {
      method: 'POST',
      body,
    });
    const html = await res.text();
    if (html.toLowerCase().includes('csrf failure')) {
      throw new Error('Index rebuild rejected (CSRF failure)');
    }

    await waitFor(
      async () => {
        const progress = await this.buildProgress();
        return progress === -1 || progress >= 100;
      },
      { timeoutMs: 120_000, intervalMs: 2_000, label: 'index rebuild' },
    );
  }
}
