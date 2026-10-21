/* Brightpath — small progressive enhancements. All forms work without JS. */
(function () {
    "use strict";

    // Auto-dismiss flash messages after a few seconds (kept accessible).
    document.querySelectorAll(".flash").forEach(function (flash) {
        setTimeout(function () {
            flash.style.opacity = "0";
            flash.style.transition = "opacity 0.4s ease";
            setTimeout(function () { flash.remove(); }, 450);
        }, 7000);
    });

    // Inline "confirm" forms: data-confirm shows a native confirmation dialog.
    document.querySelectorAll("form[data-confirm]").forEach(function (form) {
        form.addEventListener("submit", function (event) {
            var message = form.getAttribute("data-confirm") || "Are you sure?";
            if (!window.confirm(message)) {
                event.preventDefault();
            }
        });
    });

    // Rubric builder on the assignment form (data-driven, CSP-safe).
    var rubricRows = document.getElementById("rubric-rows");
    if (rubricRows) {
        var hidden = document.getElementById("rubricJson");
        var raw = document.getElementById("rubric-json-raw");
        var initialEl = document.getElementById("rubric-initial");
        var keyCounter = 1;

        function snapshot() {
            var criteria = [];
            rubricRows.querySelectorAll("tr").forEach(function (tr) {
                var name = tr.querySelector(".rb-name").value.trim();
                var desc = tr.querySelector(".rb-desc").value.trim();
                var pts = parseFloat(tr.querySelector(".rb-points").value || "0");
                if (!name) {
                    return;
                }
                criteria.push({key: "c" + keyCounter++, name: name, points: isNaN(pts) ? 0 : pts, description: desc});
            });
            hidden.value = JSON.stringify(criteria);
        }

        function addRow(criterion) {
            var tr = document.createElement("tr");
            tr.innerHTML =
                '<td><input class="input rb-name" type="text" placeholder="e.g. Structure and clarity" value="' + (criterion ? escapeHtml(criterion.name || "") : "") + '"></td>' +
                '<td><input class="input rb-desc" type="text" placeholder="What earns full points" value="' + (criterion ? escapeHtml(criterion.description || "") : "") + '"></td>' +
                '<td><input class="input rb-points" type="number" min="0" max="1000" step="0.5" value="' + (criterion ? (criterion.points || 0) : 10) + '"></td>' +
                '<td><button type="button" class="btn btn-ghost btn-sm rb-remove" aria-label="Remove criterion">✕</button></td>';
            tr.querySelector(".rb-remove").addEventListener("click", function () {
                tr.remove();
                snapshot();
            });
            rubricRows.appendChild(tr);
        }

        function escapeHtml(value) {
            return String(value).replace(/&/g, "&amp;").replace(/"/g, "&quot;")
                .replace(/</g, "&lt;").replace(/>/g, "&gt;");
        }

        var initial = [];
        try {
            initial = JSON.parse((initialEl && initialEl.textContent) || "[]") || [];
        } catch (e) { /* keep empty */ }
        if (initial.length) {
            initial.forEach(addRow);
        }
        raw.value = JSON.stringify(initial.length ? initial : [], null, 2);
        document.getElementById("add-rubric-row").addEventListener("click", function () { addRow(null); });
        rubricRows.addEventListener("input", snapshot);
        raw.addEventListener("input", function () {
            hidden.value = raw.value;
        });
        document.querySelector("form.form-card").addEventListener("submit", function () {
            snapshot();
            if (raw.value.trim()) {
                hidden.value = raw.value;
            }
        });
        snapshot();
    }
    // Export scope selector: keeps cohort selection in the CSV links.
    document.querySelectorAll("select[id^='cohort-']").forEach(function (select) {
        var courseId = select.id.replace("cohort-", "");
        select.addEventListener("change", function () {
            var suffix = select.value ? "&cohortId=" + select.value : "";
            ["grades-", "submissions-", "progress-"].forEach(function (prefix) {
                var link = document.getElementById(prefix + courseId);
                if (link) {
                    link.href = "/admin/export/" + prefix.replace("-", "") + ".csv?courseId=" + courseId + suffix;
                }
            });
        });
    });
})();
