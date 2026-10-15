import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { ShowcaseApp } from "../showcase/ShowcaseApp";
import "../showcase/showcase.css";

const root = document.getElementById("root");
if (!root) {
  throw new Error("Root element is missing.");
}
createRoot(root).render(
  <StrictMode>
    <ShowcaseApp />
  </StrictMode>,
);
