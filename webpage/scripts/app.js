let diagnosticsDb = {};
let repairsDb = {};
let currentId = null;
let currentMode = 'log';
let currentHeaders = [];
let selectedFile = null;

async function init() {
    const list = document.getElementById('queueList');
    list.innerHTML = '<li class="queue-item"><div class="queue-desc">Loading queue...</div></li>';
    try {
        const [diagRes, repRes] = await Promise.all([
            fetch('/api/diagnostics', { cache: 'no-store' }),
            fetch('/api/repairs', { cache: 'no-store' })
        ]);
        diagnosticsDb = await diagRes.json();
        repairsDb = await repRes.json();
        renderQueue();
    } catch (err) {
        list.innerHTML = '<li class="queue-item"><div class="queue-desc">Failed to connect to backend</div></li>';
    }
    checkStoredFile();
}

async function checkStoredFile() {
    try {
        const res = await fetch('/api/has-stored-file', { cache: 'no-store' });
        const data = await res.json();
        const btn = document.getElementById('btnReimport');
        btn.style.display = data.exists ? 'inline-block' : 'none';
    } catch (ignored) {}
}

async function reimportStored() {
    const btn = document.getElementById('btnReimport');
    const original = btn.innerText;
    btn.disabled = true;
    btn.innerText = "Refreshing...";

    try {
        const response = await fetch('/api/reimport', { method: 'POST' });
        const result = await response.json();
        if (result.success) {
            alert("Sheet refreshed: " + result.data);
            await init();
        } else {
            alert("Refresh failed: " + result.error);
        }
    } catch (err) {
        alert("Failed to connect to backend.");
    } finally {
        btn.disabled = false;
        btn.innerText = original;
    }
}

function displayIdFor(id) {
    const rep = repairsDb[id];
    if (rep && rep.display_id) return rep.display_id;
    return id;
}

function renderQueue() {
    const list = document.getElementById('queueList');
    list.innerHTML = '';
    for (const [id, data] of Object.entries(diagnosticsDb)) {
        const li = document.createElement('li');
        li.className = 'queue-item';
        const label = displayIdFor(id);
        const desc = (data.hardware || 'Unknown').split('|')[0].split(',')[0];
        li.innerHTML = `<div class="queue-id">${label}</div><div class="queue-desc">${desc}</div>`;
        li.onclick = () => loadPC(id, li);
        list.appendChild(li);
    }
}

function loadPC(id, element) {
    currentId = id;
    document.querySelectorAll('.queue-item').forEach(el => el.classList.remove('active'));
    if (element) element.classList.add('active');

    const data = diagnosticsDb[id];
    if (!data.chat_history) data.chat_history = [];

    document.getElementById('activeId').innerText = displayIdFor(id);
    document.getElementById('sympData').innerText = data.symptoms || 'None recorded';
    document.getElementById('hwData').innerText = data.hardware || 'None recorded';
    document.getElementById('manualNotes').value = data.notes_so_far || '';

    renderChatHistory();
}

function renderChatHistory() {
    const output = document.getElementById('aiOutput');
    const history = diagnosticsDb[currentId].chat_history;

    if (!history || history.length === 0) {
        output.innerHTML = '<span class="placeholder-text">Click "Generate Steps" to request a troubleshooting checklist...</span>';
        return;
    }

    let html = '<div class="chat-container">';
    history.forEach(msg => {
        if (msg.role === 'assistant' || msg.role === 'ai') {
            html += `<div class="chat-bubble chat-ai"><strong>Groq:</strong><br>${safeMarkdown(msg.content)}</div>`;
        } else {
            const bg = msg.type === 'ask' ? 'background-color: #E2DDD5;' : '';
            const label = msg.type === 'ask' ? 'Question' : 'Logged Note';
            html += `<div class="chat-bubble chat-user" style="${bg}"><strong>${label} (${msg.timestamp}):</strong><br>${msg.content}</div>`;
        }
    });
    html += '</div>';

    output.innerHTML = html;
    output.scrollTop = output.scrollHeight;
}

function setQuestionMode(mode) {
    currentMode = mode;
    document.getElementById('modeLog').classList.toggle('active', mode === 'log');
    document.getElementById('modeAsk').classList.toggle('active', mode === 'ask');
    const input = document.getElementById('followUpInput');
    input.placeholder = mode === 'log' ? "Add symptom to repair record (Press Enter)..." : "Ask general technical question (Press Enter)...";
    input.focus();
}

function safeMarkdown(text) {
    if (typeof marked !== 'undefined' && typeof marked.parse === 'function') {
        return marked.parse(text);
    }
    return text.replace(/\n/g, '<br>');
}

function buildApiHistory() {
    const history = diagnosticsDb[currentId]?.chat_history || [];
    return history
        .filter(msg => msg.role === 'user' || msg.role === 'assistant')
        .slice(-8)
        .map(msg => ({ role: msg.role, content: msg.content }));
}

async function simulateGroqCall() {
    if (!currentId) return alert("Select a PC first.");

    const btn = document.getElementById('btnGroq');
    const output = document.getElementById('aiOutput');
    btn.innerText = "Generating...";

    if (diagnosticsDb[currentId].chat_history.length === 0) {
        output.innerHTML = '<span class="placeholder-text">Waiting on Groq API...</span>';
    }

    try {
        const response = await fetch('/api/groq', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: currentId,
                hardware: diagnosticsDb[currentId].hardware,
                symptoms: diagnosticsDb[currentId].symptoms,
                notes: diagnosticsDb[currentId].notes_so_far,
                chat_history: buildApiHistory()
            })
        });

        const result = await response.json();
        if (result.success) {
            diagnosticsDb[currentId].chat_history.push({ role: 'assistant', content: result.data });
            await saveNotesSilently();
            renderChatHistory();
        } else {
            alert(`Error: ${result.error}`);
        }
    } catch (err) {
        alert("Failed to connect to backend.");
    } finally {
        btn.innerText = "Generate Steps";
    }
}

async function submitBenchQuery() {
    if (!currentId) return alert("Select a PC from the queue first.");

    const inputEl = document.getElementById('followUpInput');
    const query = inputEl.value.trim();
    if (!query) return;

    const sendBtn = document.getElementById('btnSubmitQuery');
    sendBtn.disabled = true;
    sendBtn.innerText = "Sending...";

    const timestamp = new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
    let notesPayload = diagnosticsDb[currentId].notes_so_far || "";

    if (currentMode === 'log') {
        const formattedNote = `\n[${timestamp} Update]: ${query}`;
        const notesEl = document.getElementById('manualNotes');
        notesEl.value += formattedNote;
        diagnosticsDb[currentId].notes_so_far = notesEl.value;
        notesPayload = notesEl.value;
    } else {
        notesPayload += `\n\n[Technician Question - Do not log to report]: ${query}`;
    }

    diagnosticsDb[currentId].chat_history.push({
        role: 'user',
        type: currentMode,
        content: query,
        timestamp: timestamp
    });

    inputEl.value = "";
    renderChatHistory();

    const container = document.querySelector('.chat-container');
    if (container) {
        container.innerHTML += `<div id="loadingBubble" class="chat-bubble chat-ai"><i>Querying Groq...</i></div>`;
        document.getElementById('aiOutput').scrollTop = document.getElementById('aiOutput').scrollHeight;
    }

    try {
        const response = await fetch('/api/groq', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: currentId,
                hardware: diagnosticsDb[currentId].hardware,
                symptoms: diagnosticsDb[currentId].symptoms,
                notes: notesPayload,
                chat_history: buildApiHistory()
            })
        });

        const result = await response.json();
        if (result.success) {
            diagnosticsDb[currentId].chat_history.push({ role: 'assistant', content: result.data });
            await saveNotesSilently();
        } else {
            alert(`Error: ${result.error}`);
        }
        renderChatHistory();
    } catch (err) {
        alert("Failed to connect to backend.");
        document.getElementById('loadingBubble')?.remove();
    } finally {
        sendBtn.disabled = false;
        sendBtn.innerText = "Send";
    }
}

async function clearAIHistory() {
    if (!currentId) return;
    if (confirm(`Are you sure you want to wipe the diagnostic history for ${currentId}?`)) {
        diagnosticsDb[currentId].chat_history = [];
        diagnosticsDb[currentId].notes_so_far = "";
        document.getElementById('manualNotes').value = "";
        await saveNotesSilently();
        renderChatHistory();
    }
}

async function saveNotes() {
    if (!currentId) return;
    diagnosticsDb[currentId].notes_so_far = document.getElementById('manualNotes').value;
    await saveNotesSilently();
    alert(`Saved local notes for ${currentId}.`);
}

async function saveNotesSilently() {
    if (!currentId) return;
    try {
        await fetch('/api/notes', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                id: currentId,
                notes: diagnosticsDb[currentId].notes_so_far,
                chat_history: diagnosticsDb[currentId].chat_history || []
            })
        });
    } catch (ignored) {}
}

// ---------- Excel import ----------

async function previewExcel() {
    const fileInput = document.getElementById('excelFile');
    if (!fileInput.files[0] && !selectedFile) {
        return alert("Please select an Excel file first.");
    }
    if (fileInput.files[0]) selectedFile = fileInput.files[0];

    const btn = document.getElementById('btnPreview');
    btn.innerText = "Reading...";

    const formData = new FormData();
    formData.append("file", selectedFile);

    try {
        const response = await fetch('/api/preview', { method: 'POST', body: formData });
        const result = await response.json();

        if (result.success) {
            const payload = JSON.parse(result.data);
            currentHeaders = payload.headers || [];
            const savedConfig = payload.config || {};

            const container = document.getElementById('radioListContainer');
            container.innerHTML = '';

            if (currentHeaders.length === 0) {
                container.innerHTML = '<div style="color:#900; font-weight:bold;">No columns found in first row.</div>';
                document.getElementById('mappingGateway').style.display = 'block';
                return;
            }

            currentHeaders.forEach((header, index) => {
                const saved = savedConfig[header];
                const role = (saved === 'id' || saved === 'symptoms' || saved === 'hardware' || saved === 'private')
                    ? saved
                    : 'private';

                const card = document.createElement('div');
                card.className = 'column-card';
                card.innerHTML = `
                    <div class="column-card-name" title="${header}">${header}</div>
                    <div class="column-card-radios">
                        <label><input type="radio" name="col_${index}" value="id" ${role === 'id' ? 'checked' : ''} onchange="saveColumnConfig()"> ID</label>
                        <label><input type="radio" name="col_${index}" value="symptoms" ${role === 'symptoms' ? 'checked' : ''} onchange="saveColumnConfig()"> Symptoms</label>
                        <label><input type="radio" name="col_${index}" value="hardware" ${role === 'hardware' ? 'checked' : ''} onchange="saveColumnConfig()"> Hardware</label>
                        <label><input type="radio" name="col_${index}" value="private" ${role === 'private' ? 'checked' : ''} onchange="saveColumnConfig()"> Private</label>
                    </div>
                `;
                container.appendChild(card);
            });

            document.getElementById('mappingGateway').style.display = 'block';
        } else {
            alert("Preview failed: " + result.error);
        }
    } catch (err) {
        console.error(err);
        alert("Server error during preview.");
    } finally {
        btn.innerText = "Preview";
    }
}

function buildMappingConfig() {
    const config = {};
    currentHeaders.forEach((header, index) => {
        const selected = document.querySelector(`input[name="col_${index}"]:checked`);
        config[header] = selected ? selected.value : 'private';
    });
    return config;
}

async function saveColumnConfig() {
    if (currentHeaders.length === 0) return;
    const config = buildMappingConfig();
    try {
        await fetch('/api/save-config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(config)
        });
    } catch (ignored) {}
}

async function executeImport() {
    if (!selectedFile) return alert("Please select an Excel file first.");

    const config = buildMappingConfig();

    const formData = new FormData();
    formData.append("file", selectedFile);
    formData.append("config", new Blob([JSON.stringify(config)], { type: "application/json" }));

    try {
        const response = await fetch('/api/import', { method: 'POST', body: formData });
        const result = await response.json();

        if (result.success) {
            alert("Import complete: " + result.data);
            document.getElementById('mappingGateway').style.display = 'none';
            document.getElementById('importPanel').style.display = 'none';
            document.getElementById('btnToggleImport').innerText = '+ Add jobs from Excel';
            selectedFile = null;
            document.getElementById('excelFile').value = "";
            await init();
        } else {
            alert("Import failed: " + result.error);
        }
    } catch (err) {
        console.error(err);
        alert("Server error during import.");
    }
}

function toggleImportPanel() {
    const panel = document.getElementById('importPanel');
    const btn = document.getElementById('btnToggleImport');
    const showing = panel.style.display !== 'none';
    panel.style.display = showing ? 'none' : 'block';
    btn.innerText = showing ? '+ Add jobs from Excel' : '− Hide import';
}

async function clearAllJobs() {
    if (!confirm("Delete all jobs from the queue? This cannot be undone.")) return;

    try {
        const response = await fetch('/api/clear-all', { method: 'POST' });
        const result = await response.json();
        if (result.success) {
            currentId = null;
            diagnosticsDb = {};
            repairsDb = {};
            renderQueue();
            document.getElementById('activeId').innerText = 'Select a PC';
            document.getElementById('sympData').innerText = '-';
            document.getElementById('hwData').innerText = '-';
            document.getElementById('manualNotes').value = '';
            document.getElementById('aiOutput').innerHTML =
                '<span class="placeholder-text">Click "Generate Steps" to request a troubleshooting checklist...</span>';
        } else {
            alert("Failed: " + result.error);
        }
    } catch (err) {
        alert("Failed to connect to backend.");
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('followUpInput');
    if (input) {
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                submitBenchQuery();
            }
        });
    }
});

window.onload = init;