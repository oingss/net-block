import { exec, toast } from 'kernelsu';

const template = document.getElementById('app-template').content;
const appsList = document.getElementById('apps-list');
const searchInput = document.getElementById('search');

const filterBtn = document.getElementById('filter-btn');
const filterModal = document.getElementById('filter-modal');
const filterOptions = [...document.querySelectorAll('.filter-option')];

// Path to pkgserver.apk relative to this module's install directory.
// Resolved at runtime because module install paths differ (Magisk vs KernelSU/APatch).
const PKGSERVER_APK = '/data/adb/modules/net-switch/pkgserver/pkgserver.apk';
const PKGSERVER_MAIN = 'com.cablebee.pkgserver.Main';

// android.content.pm.ApplicationInfo.FLAG_SYSTEM
const FLAG_SYSTEM = 0x00000001;

async function run(cmd, timeoutMs) {
    const execPromise = exec(cmd);
    const result = timeoutMs
        ? await Promise.race([
              execPromise,
              new Promise((resolve) => setTimeout(() => resolve({ errno: -1, stdout: '', stderr: 'timeout' }), timeoutMs)),
          ])
        : await execPromise;

    const { errno, stdout, stderr } = result;
    if (errno !== 0) {
        toast(`stderr: ${stderr}`);
        return undefined;
    }
    return stdout;
}

function sortChecked() {
    [...appsList.children]
        .sort((a, b) => (a.querySelector('input[type="checkbox"]').checked ? -1 : 1))
        .forEach((node) => appsList.appendChild(node));
}

const isolateList = [];

// Current display filter: 'all' | 'hide-system' | 'hide-third-party'
let currentFilter = 'all';

function applyFilters() {
    const searchVal = searchInput.value.toLowerCase();

    [...appsList.children].forEach((node) => {
        const label = node.querySelector('.app-label').textContent.toLowerCase();
        const pkg = node.querySelector('.app-pkg').textContent.toLowerCase();
        const isSystem = node.dataset.system === 'true';

        const matchesSearch = !searchVal || label.includes(searchVal) || pkg.includes(searchVal);

        let matchesFilter = true;
        if (currentFilter === 'hide-system') matchesFilter = !isSystem;
        else if (currentFilter === 'hide-third-party') matchesFilter = isSystem;

        node.style.display = matchesSearch && matchesFilter ? '' : 'none';
    });
}

function populateApp(pkg, label, isSystem, iconB64, checked) {
    const node = document.importNode(template, true);
    const root = node.querySelector('div.border');
    root.dataset.system = isSystem ? 'true' : 'false';

    const labelElement = node.querySelector('.app-label');
    labelElement.textContent = label;

    const pkgElement = node.querySelector('.app-pkg');
    pkgElement.textContent = pkg;

    const iconElement = node.querySelector('.app-icon');
    if (iconB64) {
        iconElement.src = `data:image/png;base64,${iconB64}`;
        iconElement.alt = label;
        iconElement.classList.remove('hidden');
        // Hide the icon slot if it fails to decode, rather than showing a broken image.
        iconElement.addEventListener('error', () => iconElement.classList.add('hidden'), { once: true });
    }

    const checkbox = node.querySelector('input[type="checkbox"]');
    checkbox.checked = checked;

    if (checked) isolateList.push(pkg);

    checkbox.addEventListener('change', async () => {
        const { stdout: appUid } = await exec(`grep "^${pkg}" /data/system/packages.list | awk '{print $2; exit}'`);

        if (!appUid || isNaN(appUid)) {
            toast(`Unable to fetch UID of ${pkg}.`);
            await saveIsolateList();
            return;
        }

        if (checkbox.checked) {
            isolateList.push(pkg);
            await run(`iptables -I OUTPUT -m owner --uid-owner ${appUid} -j REJECT`);
            await run(`ip6tables -I OUTPUT -m owner --uid-owner ${appUid} -j REJECT`);
        } else {
            const index = isolateList.indexOf(pkg);
            if (index !== -1) isolateList.splice(index, 1);
            await run(`iptables -D OUTPUT -m owner --uid-owner ${appUid} -j REJECT`);
            await run(`ip6tables -D OUTPUT -m owner --uid-owner ${appUid} -j REJECT`);
        }

        await saveIsolateList();
    });

    appsList.appendChild(node);
}

async function saveIsolateList() {
    await run(`echo '${JSON.stringify(isolateList)}' >/data/adb/net-switch/isolated.json`);
}

// Fetch every installed package's real display name + system/third-party
// classification in one shot, via pkgserver.apk running under app_process.
// pkgserver uses the real android.content.pm.IPackageManager + a per-APK
// Resources instance to resolve applicationInfo.labelRes -> localized string,
// which is the same mechanism Android itself uses to show app names — far
// more reliable than dumpsys text scraping or requiring aapt on-device.
//
// Output: one JSON object per line on stdout, e.g.
//   {"package":"com.foo","label":"Foo","flags":137363456,...}
//   {"package":"com.bar","error":"PackageInfo is null"}
//
// This can be slow (it walks every installed APK) or fail entirely on some
// devices/SELinux setups, so it's given a hard timeout and never allowed to
// throw — callers always get a (possibly empty) Map back, and the rest of
// the page (app list, filter modal) initializes regardless.
async function fetchPackageInfo() {
    const info = new Map();

    let out;
    try {
        out = await run(`app_process -Djava.class.path=${PKGSERVER_APK} /system/bin ${PKGSERVER_MAIN}`, 15000);
    } catch (err) {
        toast(`pkgserver failed: ${err && err.message ? err.message : err}`);
        return info;
    }

    if (!out) return info;

    for (const line of out.split('\n')) {
        const trimmed = line.trim();
        if (!trimmed || trimmed[0] !== '{') continue; // skip stray/non-JSON output

        let entry;
        try {
            entry = JSON.parse(trimmed);
        } catch {
            continue;
        }

        if (!entry.package || entry.error) continue;

        const isSystem = typeof entry.flags === 'number' && (entry.flags & FLAG_SYSTEM) !== 0;
        info.set(entry.package, {
            label: entry.label || entry.package,
            isSystem,
            icon: entry.icon || null, // base64 PNG (no data: prefix) or null
        });
    }

    return info;
}

async function main() {
    // --- Filter modal & search wiring — set up first and unconditionally,
    // so the UI stays interactive even if fetching the app list below fails
    // or times out. ---
    function setActiveFilterUI(filter) {
        filterOptions.forEach((btn) => {
            const check = btn.querySelector('.filter-check');
            check.classList.toggle('hidden', btn.dataset.filter !== filter);
        });
    }

    function openFilterModal() {
        filterModal.classList.remove('hidden');
        filterModal.classList.add('flex');
        filterBtn.setAttribute('aria-expanded', 'true');
    }

    function closeFilterModal() {
        filterModal.classList.add('hidden');
        filterModal.classList.remove('flex');
        filterBtn.setAttribute('aria-expanded', 'false');
    }

    filterBtn.addEventListener('click', openFilterModal);

    filterModal.addEventListener('click', (e) => {
        if (e.target === filterModal) closeFilterModal();
    });

    filterOptions.forEach((btn) => {
        btn.addEventListener('click', () => {
            currentFilter = btn.dataset.filter;
            setActiveFilterUI(currentFilter);
            applyFilters();
            closeFilterModal();
        });
    });

    setActiveFilterUI(currentFilter);
    searchInput.addEventListener('input', applyFilters);

    // --- Load and render the app list. Wrapped so that any failure here
    // (pkgserver hanging, pm erroring, malformed isolated.json, etc.) can
    // never leave the page blank with the filter UI unresponsive — it will
    // instead surface a toast and leave the list empty. ---
    try {
        // Fetch third-party and system packages via pm as an installed-package
        // source of truth (also used to detect uninstalled apps below).
        const pkgsOut = await run('pm list packages');
        if (pkgsOut === undefined) return;

        const installedPackages = new Set(
            pkgsOut
                .split('\n')
                .map((line) => line.split(':')[1])
                .filter(Boolean)
        );

        // Fetch isolated apps list
        const isolatedListOut = await run('cat /data/adb/net-switch/isolated.json');
        let isolated = isolatedListOut ? JSON.parse(isolatedListOut) : [];

        // Clean up uninstalled apps from isolated.json
        const updatedIsolatedList = isolated.filter((app) => installedPackages.has(app));

        if (isolated.length !== updatedIsolatedList.length) {
            await run(`echo '${JSON.stringify(updatedIsolatedList)}' >/data/adb/net-switch/isolated.json`);
            isolated = updatedIsolatedList; // Update the isolated list for the rest of the function
        }

        // Resolve real app names + system/third-party classification via pkgserver
        const pkgInfo = await fetchPackageInfo();

        // Populate the app list
        for (const pkg of installedPackages) {
            const isIsolated = isolated.includes(pkg);
            const resolved = pkgInfo.get(pkg);
            const label = resolved ? resolved.label : pkg;
            const isSystem = resolved ? resolved.isSystem : false;
            const icon = resolved ? resolved.icon : null;
            populateApp(pkg, label, isSystem, icon, isIsolated);
        }

        sortChecked();
        applyFilters();
    } catch (err) {
        toast(`Failed to load app list: ${err && err.message ? err.message : err}`);
    }
}

main();
