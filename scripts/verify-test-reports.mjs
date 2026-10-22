import { readdir, readFile } from "node:fs/promises";
import { resolve } from "node:path";
const directories = ["backend/target/surefire-reports", "backend/target/failsafe-reports"];
const required = ["BrowserJourneyIT", "ExecutionLifecycleIT", "ExecutionHttpIT", "ExecutionMigrationIT", "ExecutionOutageIT", "ApprovalBrowserIT", "ApprovalLifecycleIT", "ApprovalHttpIT", "ApprovalMigrationIT", "ApprovalContractsTest", "ApprovalBoundariesTest", "IntegrationSecurityTest", "RestOperationTest", "RestTransportTest", "IntegrationBusinessIT", "IntegrationHttpIT", "IntegrationMigrationIT", "OutboundMigrationIT", "GraphV2DefinitionTest", "WorkflowGraphMigrationIT", "AutomationIT", "AutomationMigrationIT"];
const found = new Set();
required.push("GraphDataMigrationIT");
required.push("OperationApprovalIntentTest", "OperationInputMigrationIT");
required.push("OperationApprovalGateTest");
required.push("InternalOperationTest");
required.push("BusinessBrowserIT");
const totals = { reports: 0, tests: 0, failures: 0, errors: 0, skipped: 0 };
for (const directory of directories) {
  const files = (await readdir(directory)).filter(name => /^TEST-.*\.xml$/.test(name));
  if (!files.length) throw new Error(`No test reports in ${directory}`);
  for (const file of files) {
    const xml = await readFile(resolve(directory, file), "utf8");
    const header = xml.match(/<testsuite\b([^>]*)>/)?.[1];
    if (!header) throw new Error(`Invalid test report: ${file}`);
    totals.reports++;
    for (const key of ["tests", "failures", "errors", "skipped"]) {
      const value = header.match(new RegExp(`\\b${key}="(\\d+)"`))?.[1];
      if (value === undefined) throw new Error(`Missing ${key}: ${file}`);
      totals[key] += Number(value);
      if (key === "tests" && Number(value) === 0) throw new Error(`Empty test suite: ${file}`);
    }
    for (const name of required) if (file.endsWith(`.${name}.xml`)) found.add(name);
  }
}
if (required.some(name => !found.has(name))) throw new Error("Required browser/execution reports are missing.");
if (totals.failures || totals.errors || totals.skipped) throw new Error(`Test report gate failed: ${JSON.stringify(totals)}`);
console.log(`PASS: ${JSON.stringify(totals)}`);
