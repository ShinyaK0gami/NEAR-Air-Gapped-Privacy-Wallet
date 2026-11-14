import React, { useEffect } from "react";

export type GlassiFyMode = "default" | "super";

interface GlassiFyEngineProps {
  mode: GlassiFyMode;
  scale?: number;
  blur?: number;
  brightness?: number;
  frequency?: number;
  octaves?: number;
}

/**
 * React + Custom Element bridge for Saviru/GlassiFy (https://github.com/Saviru/GlassiFy)
 * Injects the SVG `#displacementFilter` (`feTurbulence` + `feDisplacementMap` in `super` mode,
 * or subtle frosted refraction in `default` mode) and renders the ambient `.background-glows`
 * orbs so `.glassify` and `.glass-panel` cards exhibit real-time liquid glass refraction.
 */
export const GlassiFyEngine: React.FC<GlassiFyEngineProps> = ({
  mode = "default",
  scale,
  blur,
  brightness = 1.14,
  frequency = 0.012,
  octaves = 3,
}) => {
  const isSuper = mode === "super";
  const resolvedScale = scale ?? (isSuper ? 36 : 14);
  const resolvedBlur = blur ?? (isSuper ? 12 : 16);

  useEffect(() => {
    if (typeof document === "undefined") return;
    document.documentElement.style.setProperty(
      "--glassify-blur",
      `${resolvedBlur}px`
    );
    document.documentElement.style.setProperty(
      "--glassify-brightness",
      `${brightness}`
    );
  }, [resolvedBlur, brightness]);

  return (
    <>
      {/* Hidden SVG Displacement Filter Engine from Saviru/GlassiFy */}
      <svg
        aria-hidden="true"
        style={{
          position: "absolute",
          width: 0,
          height: 0,
          pointerEvents: "none",
        }}
      >
        <defs>
          {isSuper ? (
            <filter id="displacementFilter" x="-20%" y="-20%" width="140%" height="140%">
              <feTurbulence
                type="turbulence"
                baseFrequency={frequency}
                numOctaves={octaves}
                seed="7"
                result="turbulence"
              />
              <feDisplacementMap
                in="SourceGraphic"
                in2="turbulence"
                scale={resolvedScale}
                xChannelSelector="R"
                yChannelSelector="G"
              />
            </filter>
          ) : (
            <filter id="displacementFilter" x="-10%" y="-10%" width="120%" height="120%">
              <feTurbulence
                type="fractalNoise"
                baseFrequency="0.005"
                numOctaves="2"
                seed="3"
                result="refraction"
              />
              <feDisplacementMap
                in="SourceGraphic"
                in2="refraction"
                scale={resolvedScale}
                xChannelSelector="R"
                yChannelSelector="G"
              />
            </filter>
          )}
        </defs>
      </svg>

      {/* Ambient Liquid Glass Background Glow Blobs (from Saviru/GlassiFy demo/style.css) */}
      <div className="background-glows" aria-hidden="true">
        <div className="blob blob-1" />
        <div className="blob blob-2" />
        <div className="blob blob-3" />
        <div className="glassify-grid-overlay" />
      </div>
    </>
  );
};

