describe('Debug environment variables', () => {
  it('should print Cypress env variables', () => {
    const tenant = Cypress.env('C8Y_TENANT');
    const baseurl = Cypress.env('C8Y_BASEURL');
    const username = Cypress.env('C8Y_USERNAME');
    const password = Cypress.env('C8Y_PASSWORD');
    const allEnv = Cypress.env();
    
    console.log('C8Y_TENANT:', tenant);
    console.log('C8Y_BASEURL:', baseurl);
    console.log('C8Y_USERNAME:', username);
    console.log('C8Y_PASSWORD:', password);
    console.log('All env keys:', Object.keys(allEnv));
    
    throw new Error(`ENV VALUES - TENANT: ${tenant}, BASEURL: ${baseurl}, USERNAME: ${username}, PASSWORD: ${password}`);
  });
});
