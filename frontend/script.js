
const form = document.getElementById("transactionForm");
const startButton = document.getElementById("startButton");
const nextButton = document.getElementById("nextButton");

const nodes = [...document.querySelectorAll(".chain-node")];
const nodeNames = [
    "Amount Check",
    "Behavior Check",
    "Location Check",
    "Device Check",
    "Risk Scoring"
];

const pipelineStatus = document.getElementById("pipelineStatus");
const progressBar = document.getElementById("progressBar");
const stepCounter = document.getElementById("stepCounter");

const outcomeEmpty = document.getElementById("outcomeEmpty");
const outcomeContent = document.getElementById("outcomeContent");
const decisionBadge = document.getElementById("decisionBadge");
const riskScore = document.getElementById("riskScore");

const traceEmpty = document.getElementById("traceEmpty");
const traceList = document.getElementById("traceList");
const toast = document.getElementById("toast");

let investigation = null;
let currentStep = -1;
let displayedRisk = 0;
let finished = false;

function resetDashboard() {
    nodes.forEach((node) => {
        node.className = "chain-node";
        node.querySelector(".node-state").textContent = "WAITING";
    });

    currentStep = -1;
    displayedRisk = 0;
    finished = false;
    investigation = null;

    riskScore.textContent = "0";
    progressBar.style.width = "0%";
    stepCounter.textContent = "0 / 5 HANDLERS";

    pipelineStatus.textContent = "STANDBY";
    pipelineStatus.className = "flow-badge";

    nextButton.disabled = true;
    nextButton.innerHTML = 'Next handler <span>→</span>';

    outcomeEmpty.hidden = false;
    outcomeContent.hidden = true;
    traceEmpty.hidden = false;
    traceList.replaceChildren();
}

function showError(message) {
    toast.textContent = message;
    toast.hidden = false;
}

function markSkipped(fromIndex) {
    for (let i = fromIndex; i < nodes.length; i++) {
        nodes[i].className = "chain-node skipped";
        nodes[i].querySelector(".node-state").textContent = "SKIPPED";
    }
}

function updateDecision() {
    decisionBadge.textContent = investigation.decision.toUpperCase();
    decisionBadge.className = "decision-badge";

    if (investigation.decision === "Approved") {
        decisionBadge.classList.add("approved");
    } else if (investigation.decision === "Rejected") {
        decisionBadge.classList.add("rejected");
    } else {
        decisionBadge.classList.add("review");
    }

    outcomeEmpty.hidden = true;
    outcomeContent.hidden = false;
    riskScore.textContent = displayedRisk;
}

function finishInvestigation() {
    finished = true;
    nextButton.disabled = true;
    nextButton.innerHTML = "Investigation complete ✓";

    pipelineStatus.textContent = "COMPLETE";
    pipelineStatus.className = "flow-badge complete";

    // Show the actual final score and decision returned by Java.
    displayedRisk = investigation.riskScore;
    riskScore.textContent = investigation.riskScore;
    updateDecision();
}

function showCurrentStep(index) {
    const message = investigation.logs[index];
    const node = nodes[index];

    node.classList.remove("active", "done", "flagged", "rejected");
    node.classList.add("active");

    const state = node.querySelector(".node-state");
    state.textContent = "PROCESSING";

    const isCritical = /critical amount|transaction rejected/i.test(message);
    const isRisk = /\+\d+ risk points/i.test(message);

    // The Java handler's returned message determines the visual state.
    if (isCritical) {
        node.classList.remove("active");
        node.classList.add("rejected");
        state.textContent = "REJECTED";
    } else if (isRisk) {
        node.classList.remove("active");
        node.classList.add("flagged");
        state.textContent = "RISK FOUND";

        const points = message.match(/\(\+(\d+) risk points\)/i);
        if (points) {
            displayedRisk += Number(points[1]);
        }
    } else {
        node.classList.remove("active");
        node.classList.add("done");
        state.textContent = "PASSED";
    }

    riskScore.textContent = displayedRisk;

    const logItem = document.createElement("li");
    logItem.textContent = `${nodeNames[index]}: ${message}`;

    if (isCritical) {
        logItem.classList.add("critical-log");
    } else if (isRisk) {
        logItem.classList.add("risk-log");
    }

    traceList.appendChild(logItem);
    traceEmpty.hidden = true;

    currentStep = index;

    const isLastLog = currentStep >= investigation.logs.length - 1;

    progressBar.style.width =
        `${((currentStep + 1) / nodes.length) * 100}%`;

    stepCounter.textContent =
        `${currentStep + 1} / ${nodes.length} HANDLERS`;

    pipelineStatus.textContent = isLastLog ? "FINALIZING" : "IN PROGRESS";
    pipelineStatus.className = "flow-badge running";

    if (isCritical) {
        // Critical amount stops the chain immediately.
        markSkipped(index + 1);
        progressBar.style.width = `${100 / nodes.length}%`;
        finishInvestigation();
        return;
    }

    if (isLastLog) {
        finishInvestigation();
    } else {
        nextButton.disabled = false;
        nextButton.innerHTML = 'Next handler <span>→</span>';
    }
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const amount = Number(form.elements.amount.value);
    const transfers = Number(form.elements.transfers.value);

    if (!Number.isFinite(amount) || amount <= 0 ||
        !Number.isInteger(transfers) || transfers < 0) {
        showError("Enter a positive amount and a valid transfer count.");
        return;
    }

    resetDashboard();
    startButton.disabled = true;
    startButton.innerHTML = "Connecting to Java...";

    try {
        const formData = new URLSearchParams(new FormData(form));

        const response = await fetch("/investigate", {
            method: "POST",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded"
            },
            body: formData
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.error || "The Java investigation failed.");
        }

        if (!Array.isArray(data.logs) || data.logs.length === 0) {
            throw new Error("The Java backend returned no handler logs.");
        }

        investigation = data;

        pipelineStatus.textContent = "READY";
        pipelineStatus.className = "flow-badge running";

        // Show the first handler. The remaining trace is revealed with Next.
        showCurrentStep(0);

    } catch (error) {
        showError(
            `${error.message} Make sure the Java server is running on this page.`
        );
        pipelineStatus.textContent = "CONNECTION ERROR";
        pipelineStatus.className = "flow-badge";
    } finally {
        startButton.disabled = false;
        startButton.innerHTML = "<span>▶</span> Start investigation";
    }
});

nextButton.addEventListener("click", () => {
    if (!investigation || finished) return;

    const nextIndex = currentStep + 1;

    if (nextIndex < investigation.logs.length) {
        showCurrentStep(nextIndex);
    }
});

resetDashboard();
