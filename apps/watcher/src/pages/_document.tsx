import { Html, Head, Main, NextScript } from "next/document";

export default function Document() {
  return (
    <Html lang="en" className="dark">
      <Head>
        <meta
          name="description"
          content="NearAir (AirNEAR) — Open-source, zero-cost, air-gapped cold storage signing system for NEAR Protocol"
        />
        <link rel="icon" href="/icon.jpg" />
        <link rel="apple-touch-icon" href="/icon.jpg" />
        <meta property="og:image" content="/icon.jpg" />
      </Head>
      <body className="bg-[#090d16] text-slate-100 antialiased">
        <Main />
        <NextScript />
      </body>
    </Html>
  );
}

