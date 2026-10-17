import { checkTypes, CommandFailure } from '../api/process.ts';

checkTypes(process.argv.slice(2)).catch(error => {
  if (!(error instanceof CommandFailure)) console.error(error.message);
  process.exitCode = error instanceof CommandFailure ? error.exitCode : 1;
});
