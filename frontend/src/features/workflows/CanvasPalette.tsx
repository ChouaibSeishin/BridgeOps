import {
  Bell,
  CircleStop,
  Clock,
  Code2,
  GitBranch,
  Globe,
  MousePointer2,
  Play,
  ShieldCheck,
  Webhook,
  Zap,
} from "lucide-react";
import { canvasTypes } from "./canvasModel";

const categories = [
  { name: "Triggers", types: ["manualTrigger", "webhookTrigger"] },
  {
    name: "Actions",
    types: [
      "syntheticAction",
      "capabilityAction",
      "configuredHttp",
      "notification",
    ],
  },
  {
    name: "Flow control",
    types: ["condition", "approval", "delay", "transform", "end"],
  },
];
const icons = {
  manualTrigger: Play,
  webhookTrigger: Webhook,
  syntheticAction: Zap,
  capabilityAction: MousePointer2,
  configuredHttp: Globe,
  notification: Bell,
  condition: GitBranch,
  approval: ShieldCheck,
  delay: Clock,
  transform: Code2,
  end: CircleStop,
};

/** Presentation only; the existing canvas model still owns additions and IDs. */
export function CanvasPalette({
  schemaVersion,
  disabled,
  full,
  type,
  selectType,
  add,
}: {
  schemaVersion: number;
  disabled: boolean;
  full: boolean;
  type: string;
  selectType: (type: string) => void;
  add: (type: string) => void;
}) {
  const allowed = (type: string) =>
    schemaVersion === 2 ||
    ["manualTrigger", "syntheticAction", "approval", "end"].includes(type);
  return (
    <aside className="canvas-palette" aria-label="Node palette">
      <h3>Node palette</h3>
      <p className="muted">Add a step, then connect it.</p>
      {categories.map((category) => (
        <section
          className="palette-category"
          key={category.name}
          aria-label={category.name}
        >
          <h4>{category.name}</h4>
          {category.types.filter(allowed).map((kind) => {
            const Icon = icons[kind as keyof typeof icons];
            return (
              <button
                key={kind}
                type="button"
                className="palette-node"
                disabled={disabled || full}
                onClick={() => add(kind)}
                aria-label={`Add ${canvasTypes[kind]} node`}
              >
                <Icon size={17} aria-hidden="true" />
                <span>{canvasTypes[kind]}</span>
                <span className="palette-plus" aria-hidden="true">
                  +
                </span>
              </button>
            );
          })}
        </section>
      ))}
      <div className="palette-keyboard">
        <label>
          Canvas node type
          <select
            value={type}
            disabled={disabled}
            onChange={(event) => selectType(event.target.value)}
          >
            {categories.map((category) => (
              <optgroup key={category.name} label={category.name}>
                {category.types.filter(allowed).map((kind) => (
                  <option key={kind} value={kind}>
                    {canvasTypes[kind]}
                  </option>
                ))}
              </optgroup>
            ))}
          </select>
        </label>
        <button
          type="button"
          className="button"
          disabled={disabled || full}
          onClick={() => add(type)}
        >
          Add canvas node
        </button>
        {full && <p className="muted">The 50-node limit has been reached.</p>}
      </div>
    </aside>
  );
}
