let diagnosticsDb = {};
let currentId = null;
let currentMode = 'log';

async function init() {
    const list = document.getElementById('queueList');
    list.innerHTML = '<li class="queue-item"><div class="queue-desc">Loading queue...</div></li>';
    try {
        const response = await fetch('/api/diagnostics', { cache: 'no-store' });
        diagnosticsDb = await response.json();
        renderQueue();
    } catch (err) {
        list.innerHTML = '<li class="queue-item"><div class="queue-desc">Failed to connect to backend</div></li>';
    }
}

function renderQueue() {
    const list = document.getElementById('queueList');
    list.innerHTML = '';
    for (const [id, data] of Object.entries(diagnosticsDb)) {
        const li = document.createElement('li');
        li.className = 'queue-item';
        li.innerHTML = `<div class="queue-id">${id}</div><div class="queue-desc">${(data.hardware || 'Unknown').split(',')[0]}</div>`;
        li.onclick = () => loadPC(id, li);
        list.appendChild(li);
    }
}

// Automatically load the saved chat history when clicking a PC
function loadPC(id, element) {
    currentId = id;
    document.querySelectorAll('.queue-item').forEach(el => el.classList.remove('active'));
    if (element) element.classList.add('active');

    const data = diagnosticsDb[id];
    // Ensure the array exists
    if (!data.chat_history) data.chat_history = [];

    document.getElementById('activeId').innerText = `Unit ID: ${id}`;
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
        if (msg.role === 'assistant') {
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

// Build the API-shaped history payload from our internal chat_history.
// Strips our internal-only fields (type, timestamp) that the API doesn't accept.
function buildApiHistory() {
    const history = diagnosticsDb[currentId].chat_history || [];
    return history
        .filter(msg => msg.role === 'user' || msg.role === 'assistant')
        .slice(-8)
        .map(msg => ({ role: msg.role, content: msg.content }));
}
// Update Groq call to push to the array and save
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
            // Save the AI response permanently
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

// Update Bench Query to push to the array and save
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

    // Save the user's message to history (internal shape with type/timestamp for display)
    diagnosticsDb[currentId].chat_history.push({
        role: 'user',
        type: currentMode,
        content: query,
        timestamp: timestamp
    });

    inputEl.value = "";
    renderChatHistory(); // Show user bubble immediately

    // Add temporary loading indicator
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

// Erase chat history array for the unit
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