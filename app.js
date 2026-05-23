document.addEventListener('DOMContentLoaded', () => {
    // Theme Toggle Handler
    const themeToggleBtn = document.getElementById('theme-toggle-btn');
    const themeToggleIcon = document.getElementById('theme-toggle-icon');

    // Check and apply stored theme preference
    const savedTheme = localStorage.getItem('theme');
    if (savedTheme === 'light') {
        document.documentElement.classList.add('light-mode');
        if (themeToggleIcon) themeToggleIcon.innerText = '☀️';
    } else {
        document.documentElement.classList.remove('light-mode');
        if (themeToggleIcon) themeToggleIcon.innerText = '🌙';
    }

    if (themeToggleBtn && themeToggleIcon) {
        themeToggleBtn.addEventListener('click', () => {
            const isLight = document.documentElement.classList.toggle('light-mode');
            localStorage.setItem('theme', isLight ? 'light' : 'dark');
            themeToggleIcon.innerText = isLight ? '☀️' : '🌙';
        });
    }

    // Header Scroll Styling
    const header = document.querySelector('header');
    window.addEventListener('scroll', () => {
        if (window.scrollY > 50) {
            header.classList.add('scrolled');
        } else {
            header.classList.remove('scrolled');
        }
    });

    // Mobile Menu Toggle
    const menuToggle = document.querySelector('.menu-toggle');
    const navLinks = document.querySelector('.nav-links');
    if (menuToggle && navLinks) {
        menuToggle.addEventListener('click', () => {
            navLinks.classList.toggle('active');
            const spans = menuToggle.querySelectorAll('span');
            spans[0].style.transform = navLinks.classList.contains('active') ? 'rotate(45deg) translate(6px, 6px)' : 'none';
            spans[1].style.opacity = navLinks.classList.contains('active') ? '0' : '1';
            spans[2].style.transform = navLinks.classList.contains('active') ? 'rotate(-45deg) translate(6px, -6px)' : 'none';
        });
    }

    // Audio Orb Particle Generation
    const orbWrapper = document.querySelector('.orb-wrapper');
    if (orbWrapper) {
        // Spawn floating particles around the orb only when tab is visible
        setInterval(() => {
            if (document.visibilityState === 'visible') {
                createParticle(orbWrapper);
            }
        }, 500);
    }

    const particleColors = ['#00FF88', '#00E5FF', '#8B5CF6', '#059669'];

    function createParticle(parent) {
        // Prevent memory leak and DOM buildup by capping active particles
        const existingParticles = parent.querySelectorAll('.floating-particle');
        if (existingParticles.length >= 20) {
            existingParticles[0].remove();
        }

        const rect = parent.getBoundingClientRect();
        const width = rect.width;
        const height = rect.height;

        const particle = document.createElement('div');
        particle.classList.add('floating-particle');
        
        // Random placement relative to actual wrapper dimensions
        const x = (width / 2) + (Math.random() - 0.5) * (width * 0.6);
        const y = (height / 2) + (Math.random() - 0.5) * (height * 0.6);
        particle.style.left = `${x}px`;
        particle.style.top = `${y}px`;

        // Select a color matching the fluid orb gradients
        const color = particleColors[Math.floor(Math.random() * particleColors.length)];
        particle.style.backgroundColor = color;
        particle.style.boxShadow = `0 0 10px ${color}`;

        // Random vector path
        const dx = (Math.random() - 0.5) * 200;
        const dy = -120 - Math.random() * 160;
        particle.style.setProperty('--dx', `${dx}px`);
        particle.style.setProperty('--dy', `${dy}px`);

        // Random sizing & timing
        const size = Math.random() * 5 + 2.5;
        particle.style.width = `${size}px`;
        particle.style.height = `${size}px`;
        particle.style.animationDuration = `${Math.random() * 3 + 4}s`;

        parent.appendChild(particle);

        // Remove particle after animation finishes
        setTimeout(() => {
            particle.remove();
        }, 7000);
    }

    // Interactive Bot Click Action
    const botAvatar = document.querySelector('.bot-avatar');
    if (botAvatar) {
        botAvatar.addEventListener('click', () => {
            // Momentarily scale-up and enhance drop-shadow
            botAvatar.style.transform = 'scale(1.08) translateY(-12px)';
            botAvatar.style.filter = 'drop-shadow(0 25px 45px rgba(0, 255, 136, 0.45)) drop-shadow(0 10px 30px rgba(0, 229, 255, 0.35))';
            
            setTimeout(() => {
                botAvatar.style.transform = '';
                botAvatar.style.filter = '';
            }, 1000);
            
            // Log interaction to terminal if demo is available
            const consoleBody = document.querySelector('.console-body');
            if (consoleBody) {
                const triggerLog = document.createElement('div');
                triggerLog.className = 'console-log console-input-line';
                triggerLog.innerHTML = `<span>&gt;</span><span>Voice Trigger detected: "Wake up, OpenDroid"</span>`;
                consoleBody.appendChild(triggerLog);
                consoleBody.scrollTop = consoleBody.scrollHeight;
                
                // Automatically run the active demo
                runActiveDemo();
            }
        });
    }

    // Benchmark Simulator Setup
    const demoOptions = {
        rain: {
            prompt: "Check rain forecast, if wet message Wife that I'll be late, and schedule alarm for 6 PM.",
            logs: [
                { type: 'input', text: 'Prompt received: "Check rain forecast, if wet message Wife that I\'ll be late, and schedule alarm for 6 PM."' },
                { type: 'output', text: 'Initializing Intent Classifier... Context resolved to MULTI_STEP.' },
                { type: 'output', text: 'Invoking local Planner LLM (Gemma-2B/Ollama)...' },
                { type: 'success', text: 'Plan generated with 3 actions (Total latency: 194ms):' },
                { type: 'success', text: '  1. GET_WEATHER (params: location="current")' },
                { type: 'success', text: '  2. SEND_SMS (params: recipient="Wife", body="I\'ll be late due to weather")' },
                { type: 'success', text: '  3. SET_ALARM (params: hour=18, minute=0, label="Weather Delay")' },
                { type: 'output', text: 'Executing step 1: GET_WEATHER...' },
                { type: 'success', text: '  -> Result: Current weather: rain (heavy showers, 94% humidity). Latency: 22ms.' },
                { type: 'output', text: 'Executing step 2: SEND_SMS (Conditional branch met)...' },
                { type: 'output', text: '  -> Querying ContactResolver for "Wife"...' },
                { type: 'success', text: '  -> Fuzzy match resolved: "Jane Doe" (+155501993). Latency: 5ms.' },
                { type: 'output', text: '  -> Checking permissions: SEND_SMS -> Granted.' },
                { type: 'success', text: '  -> SMS dispatched via Telephony API. Latency: 8ms.' },
                { type: 'output', text: 'Executing step 3: SET_ALARM...' },
                { type: 'output', text: '  -> Injecting alarm intent to system manager...' },
                { type: 'success', text: '  -> Alarm registered at 6:00 PM (18:00). Latency: 11ms.' },
                { type: 'success', text: 'Execution Complete. All 3 actions completed. System status: Idle. Overall Time: 240ms.' }
            ],
            meters: { parser: 92, vision: 0, dispatcher: 98, telephony: 95 }
        },
        screen: {
            prompt: "Analyze the screen, translate any Spanish text to English, and copy it.",
            logs: [
                { type: 'input', text: 'Prompt received: "Analyze the screen, translate any Spanish text to English, and copy it."' },
                { type: 'output', text: 'Initializing Intent Classifier... Context resolved to SYSTEM_VISION.' },
                { type: 'output', text: 'Invoking Vision Engine (Multimodal screenshot ingestion)...' },
                { type: 'output', text: '  -> Requesting screen buffer from OpenDroidAccessibilityService...' },
                { type: 'success', text: '  -> Ingested layout screenshot (1080x2400 WebP). Latency: 44ms.' },
                { type: 'output', text: '  -> Dispatching screen matrix to Vision LLM...' },
                { type: 'success', text: '  -> LLM vision parsing complete. Latency: 215ms.' },
                { type: 'success', text: '  -> Extracted raw text: "Oferta especial de hoy: 50% de descuento en suscripciones."' },
                { type: 'output', text: 'Initializing Translation Action...' },
                { type: 'success', text: '  -> Translated: "Today\'s special offer: 50% off on subscriptions." Latency: 18ms.' },
                { type: 'output', text: 'Executing COPY_TO_CLIPBOARD...' },
                { type: 'success', text: '  -> Text copied to Android ClipboardManager. Latency: 3ms.' },
                { type: 'success', text: 'Execution Complete. Screen translated and copied. System status: Idle. Overall Time: 280ms.' }
            ],
            meters: { parser: 30, vision: 94, dispatcher: 90, telephony: 0 }
        },
        morning: {
            prompt: "Run my morning routine macro.",
            logs: [
                { type: 'input', text: 'Prompt received: "Run my morning routine macro."' },
                { type: 'output', text: 'Initializing Intent Classifier... Context resolved to MACRO_EXECUTION.' },
                { type: 'output', text: 'Fetching macro "morning_routine" from Room DB repository...' },
                { type: 'success', text: 'Found macro "morning_routine" with 4 steps. Latency: 4ms.' },
                { type: 'output', text: 'Executing step 1: TOGGLE_FLASHLIGHT (state=OFF)...' },
                { type: 'success', text: '  -> Flashlight disabled. Latency: 6ms.' },
                { type: 'output', text: 'Executing step 2: SET_RINGER_MODE (mode="NORMAL")...' },
                { type: 'success', text: '  -> Ringer set to Normal (Sound enabled). Latency: 9ms.' },
                { type: 'output', text: 'Executing step 3: TOGGLE_WIFI (state=ON)...' },
                { type: 'success', text: '  -> Wifi hardware enabled. Latency: 8ms.' },
                { type: 'output', text: 'Executing step 4: TTS_SPEAK (text="Good morning! Your dashboard is ready")...' },
                { type: 'output', text: '  -> Calling ElevenLabs TTS Engine (Fallback to Android native)...' },
                { type: 'success', text: '  -> Audio stream synthesized and output started. Latency: 140ms.' },
                { type: 'success', text: 'Execution Complete. Morning routine finished. System status: Idle. Overall Time: 167ms.' }
            ],
            meters: { parser: 15, vision: 0, dispatcher: 96, telephony: 10 }
        }
    };

    let activeDemoKey = 'rain';
    let isDemoRunning = false;

    const demoOptionBtns = document.querySelectorAll('.demo-option-btn');
    const runBtn = document.getElementById('run-demo-btn');
    const consoleBody = document.querySelector('.console-body');

    if (demoOptionBtns) {
        demoOptionBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                if (isDemoRunning) return;
                
                // Clear active states
                demoOptionBtns.forEach(b => b.classList.remove('active'));
                
                // Set active
                btn.classList.add('active');
                activeDemoKey = btn.dataset.demo;
                
                // Clear console and show prompt
                if (consoleBody) {
                    consoleBody.innerHTML = `
                        <div class="console-log console-input-line">
                            <span>&gt;</span><span>System Idle. Ready to execute: "${demoOptions[activeDemoKey].prompt}"</span>
                        </div>
                    `;
                }
                resetMeters();
            });
        });
    }

    if (runBtn) {
        runBtn.addEventListener('click', () => {
            runActiveDemo();
        });
    }

    function runActiveDemo() {
        if (isDemoRunning) return;
        isDemoRunning = true;
        runBtn.disabled = true;
        runBtn.innerText = 'Executing...';
        
        if (consoleBody) {
            consoleBody.innerHTML = '';
        }
        resetMeters();

        const data = demoOptions[activeDemoKey];
        let logIndex = 0;

        function printNextLog() {
            if (logIndex < data.logs.length) {
                const log = data.logs[logIndex];
                const logDiv = document.createElement('div');
                logDiv.className = `console-log console-${log.type}-line`;
                
                if (log.type === 'input') {
                    logDiv.innerHTML = `<span>&gt;</span><span>${log.text}</span>`;
                } else {
                    logDiv.innerText = log.text;
                }
                
                if (consoleBody) {
                    consoleBody.appendChild(logDiv);
                    consoleBody.scrollTop = consoleBody.scrollHeight;
                }

                // Dynamically update progress meters as logs proceed
                if (logIndex === 3) {
                    updateMeter('parser', data.meters.parser);
                } else if (logIndex === 6 && data.meters.vision > 0) {
                    updateMeter('vision', data.meters.vision);
                } else if (logIndex === 8) {
                    updateMeter('dispatcher', data.meters.dispatcher);
                } else if (logIndex === 11 && data.meters.telephony > 0) {
                    updateMeter('telephony', data.meters.telephony);
                }

                logIndex++;
                const delay = log.type === 'input' ? 400 : (log.text.includes('Invoking') ? 900 : 250);
                setTimeout(printNextLog, delay);
            } else {
                updateMeter('parser', data.meters.parser);
                updateMeter('vision', data.meters.vision);
                updateMeter('dispatcher', data.meters.dispatcher);
                updateMeter('telephony', data.meters.telephony);
                
                isDemoRunning = false;
                runBtn.disabled = false;
                runBtn.innerText = 'Test Autonomous Flow';
            }
        }

        printNextLog();
    }

    function updateMeter(id, value) {
        const bar = document.getElementById(`meter-bar-${id}`);
        const valText = document.getElementById(`meter-val-${id}`);
        if (bar && valText) {
            bar.style.width = `${value}%`;
            valText.innerText = value > 0 ? `${value}%` : '0%';
        }
    }

    function resetMeters() {
        ['parser', 'vision', 'dispatcher', 'telephony'].forEach(id => {
            const bar = document.getElementById(`meter-bar-${id}`);
            const valText = document.getElementById(`meter-val-${id}`);
            if (bar && valText) {
                bar.style.width = '0%';
                valText.innerText = '0%';
            }
        });
    }

    // Interactive Architecture Map
    const archNodes = document.querySelectorAll('.arch-node');
    const archTitle = document.getElementById('arch-details-title');
    const archDesc = document.getElementById('arch-details-desc');
    const archList = document.getElementById('arch-details-list');

    const nodeDetails = {
        voice: {
            title: "Voice Input & Wake Word",
            desc: "The entry point for hands-free local control. Monitors environmental audio offsets completely offline.",
            bullets: [
                "Porcupine Wake Word engine (offline listening)",
                "Android native speech recognition (Speech-to-Text)",
                "Seamless fallback: switches to manual chat input on speech failure",
                "High fidelity TTS fallback with ElevenLabs API support"
            ]
        },
        planner: {
            title: "LLM Planner & Re-Evaluator",
            desc: "The brains of the agent. Converts natural language instructions into actionable, structured execution plans.",
            bullets: [
                "Intent Classifier determines simple vs compound requests",
                "Structured planning generates actions, parameters, and checks",
                "Re-Evaluation Engine loops after each step to verify the system state",
                "PlanValidator checks for plan sanity and resolves logical loops"
            ]
        },
        memory: {
            title: "Multi-Tier Persistent Memory",
            desc: "Retains context and learns preferences over time across four distinct, isolated layers.",
            bullets: [
                "Working Memory: Temporary variables for the current active plan",
                "Episodic Memory: Room DB storage logging details of past execution steps",
                "Semantic Memory: structured facts extracted from chats via FactExtractor LLM",
                "Procedural Memory: Custom workflow actions saved as executable macros"
            ]
        },
        dispatcher: {
            title: "Action Dispatcher",
            desc: "Translates planner decisions into native system actions, executing calls, and toggling hardware features.",
            bullets: [
                "System controls (Flashlight, Wi-Fi, Bluetooth, Alarms, Hotspot)",
                "Communication commands (Calls, SMS, Email, WhatsApp API integration)",
                "Action Auto-Mapper maps minor variations dynamically",
                "Robust fallbacks to standard system intents when background actions fail"
            ]
        },
        accessibility: {
            title: "Accessibility Service",
            desc: "The automation powerhouse that inspects, types on, and controls third-party applications.",
            bullets: [
                "OpenDroidAccessibilityService scans layout hierarchies",
                "Clicks exact view coordinates, IDs, or string patterns",
                "Inputs text into dynamic elements and scrolls interfaces",
                "Screen buffer analyzer feeds screenshots to Vision LLM fallback"
            ]
        }
    };

    if (archNodes && archTitle && archDesc && archList) {
        archNodes.forEach(node => {
            node.addEventListener('click', () => {
                archNodes.forEach(n => n.classList.remove('active'));
                node.classList.add('active');
                
                const key = node.dataset.node;
                const details = nodeDetails[key];
                
                archTitle.innerText = details.title;
                archDesc.innerText = details.desc;
                
                archList.innerHTML = '';
                details.bullets.forEach(bullet => {
                    const li = document.createElement('li');
                    li.innerText = bullet;
                    archList.appendChild(li);
                });
            });
        });
    }

    // Commands Database Table filtering
    const categoryBtns = document.querySelectorAll('.tab-btn');
    const searchInput = document.getElementById('cmd-search');
    const tableRows = document.querySelectorAll('.cmd-table tbody tr');

    let activeCategory = 'all';
    let searchQuery = '';

    if (categoryBtns) {
        categoryBtns.forEach(btn => {
            btn.addEventListener('click', () => {
                categoryBtns.forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                activeCategory = btn.dataset.category;
                filterCommands();
            });
        });
    }

    if (searchInput) {
        searchInput.addEventListener('input', (e) => {
            searchQuery = e.target.value.toLowerCase().trim();
            filterCommands();
        });
    }

    function filterCommands() {
        tableRows.forEach(row => {
            const name = row.querySelector('.cmd-name').innerText.toLowerCase();
            const desc = row.querySelector('.cmd-desc').innerText.toLowerCase();
            const category = row.dataset.category;

            const matchesSearch = name.includes(searchQuery) || desc.includes(searchQuery);
            const matchesCategory = activeCategory === 'all' || category === activeCategory;

            if (matchesSearch && matchesCategory) {
                row.style.display = '';
            } else {
                row.style.display = 'none';
            }
        });
    }
});
