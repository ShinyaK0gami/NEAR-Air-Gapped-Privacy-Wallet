/*
 * GlassiFy 2.0.2511 - https://github.com/Saviru/GlassiFy
 * Open-source lightweight Web Component for creating liquid glass effects with dynamic displacement.
 * © 2025 Saviru Kashmira Atapattu. MIT License.
 */

class GlassiFy extends HTMLElement {
  static get observedAttributes() {
    return ["mode", "frequency", "octaves", "scale", "blur", "brightness"];
  }

  constructor() {
    super();
    this.attachShadow({ mode: "open" });
  }

  connectedCallback() {
    this.renderGlassiFy();
  }

  attributeChangedCallback() {
    if (this.isConnected) {
      this.renderGlassiFy();
    }
  }

  renderGlassiFy() {
    const mode = this.getAttribute("mode") || "default";
    const isSuper = mode === "super";

    const freq = parseFloat(this.getAttribute("frequency")) || 0.012;
    const octaves = parseInt(this.getAttribute("octaves")) || 3;
    const scale = parseInt(this.getAttribute("scale")) || (isSuper ? 38 : 180);
    const blur = parseInt(this.getAttribute("blur")) || (isSuper ? 12 : 16);
    const brightness = parseFloat(this.getAttribute("brightness")) || 1.14;

    let svgContainer = document.getElementById("glassify-svg-defs");
    if (!svgContainer) {
      svgContainer = document.createElement("div");
      svgContainer.id = "glassify-svg-defs";
      svgContainer.style.display = "none";
      document.body.appendChild(svgContainer);
    }

    if (isSuper) {
      svgContainer.innerHTML = `
        <svg style="display: none;">
          <filter id="displacementFilter">
            <feTurbulence type="turbulence" baseFrequency="${freq}" numOctaves="${octaves}" result="turbulence" />
            <feDisplacementMap in="SourceGraphic" in2="turbulence" scale="${scale}" xChannelSelector="R" yChannelSelector="G" />
          </filter>
        </svg>`;
    } else {
      svgContainer.innerHTML = `
        <svg style="display: none;">
          <filter id="displacementFilter">
            <feTurbulence type="fractalNoise" baseFrequency="0.006" numOctaves="2" result="refractionMap" />
            <feDisplacementMap in="SourceGraphic" in2="refractionMap" scale="16" xChannelSelector="R" yChannelSelector="G" />
          </filter>
        </svg>`;
    }

    document.documentElement.style.setProperty("--glassify-blur", `${blur}px`);
    document.documentElement.style.setProperty("--glassify-brightness", `${brightness}`);
  }
}

if (typeof window !== "undefined" && !customElements.get("glassi-fy")) {
  customElements.define("glassi-fy", GlassiFy);
}

