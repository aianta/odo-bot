package ca.ualberta.odobot.task.webarena;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.List;

/**
 * The Playwright locators WebArena's {@code auto_login.py} uses, over Selenium, with Playwright's matching rules:
 * text is whitespace-normalized; {@code exact} means a case-sensitive full match, otherwise a case-insensitive
 * substring. Like Playwright, they wait for the element, and prefer visible ones.
 */
public final class PlaywrightLocators {

    private PlaywrightLocators(){}

    private static final Duration WAIT = Duration.ofSeconds(30);

    private static final String FIND_JS = """
            const kind = arguments[0], wanted = arguments[1], exact = arguments[2];
            const norm = s => (s || '').replace(/\\s+/g, ' ').trim();
            const matches = text => exact ? norm(text) === wanted : norm(text).toLowerCase().includes(wanted.toLowerCase());
            const visible = e => { const r = e.getBoundingClientRect(); const st = getComputedStyle(e);
                                   return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none'; };
            const texts = e => {
              const out = [];
              if (kind === 'label') {
                for (const l of (e.labels || [])) out.push(l.innerText || l.textContent);
                if (e.getAttribute('aria-label')) out.push(e.getAttribute('aria-label'));
                for (const id of (e.getAttribute('aria-labelledby') || '').split(/\\s+/).filter(x => x)) {
                  const l = document.getElementById(id); if (l) out.push(l.innerText || l.textContent);
                }
              } else if (kind === 'placeholder') {
                out.push(e.getAttribute('placeholder'));
              } else if (kind === 'button') {
                if (e.getAttribute('aria-label')) out.push(e.getAttribute('aria-label'));
                else if (e.tagName === 'INPUT') out.push(e.value);
                else out.push(e.innerText || e.textContent);
              }
              return out;
            };
            const selector = kind === 'label' ? 'input,textarea,select'
                           : kind === 'placeholder' ? '[placeholder]'
                           : 'button,input[type=submit],input[type=button],input[type=reset],[role=button]';
            const found = [...document.querySelectorAll(selector)].filter(e => texts(e).some(matches));
            return found.filter(visible).concat(found.filter(e => !visible(e)));
            """;

    @SuppressWarnings("unchecked")
    private static WebElement find(WebDriver driver, String kind, String wanted, boolean exact){
        return new WebDriverWait(driver, WAIT).until(d->{
            List<WebElement> found = (List<WebElement>) ((JavascriptExecutor) d).executeScript(FIND_JS, kind, wanted, exact);
            return found == null || found.isEmpty()? null : found.get(0);
        });
    }

    /** {@code page.get_by_label(text, exact=exact)} */
    public static WebElement byLabel(WebDriver driver, String text, boolean exact){
        return find(driver, "label", text, exact);
    }

    /** {@code page.get_by_placeholder(text)} */
    public static WebElement byPlaceholder(WebDriver driver, String text){
        return find(driver, "placeholder", text, false);
    }

    /** {@code page.get_by_role("button", name=name)} */
    public static WebElement buttonByName(WebDriver driver, String name){
        return find(driver, "button", name, false);
    }

    /** {@code page.get_by_test_id(id)}, Playwright's default test id attribute being {@code data-testid}. */
    public static WebElement byTestId(WebDriver driver, String id){
        return new WebDriverWait(driver, WAIT).ignoring(NoSuchElementException.class)
                .until(d->d.findElement(By.cssSelector("[data-testid=\"" + id.replace("\"", "\\\"") + "\"]")));
    }

    /** Playwright's {@code fill}: replace the value. */
    public static void fill(WebElement element, String value){
        element.clear();
        element.sendKeys(value);
    }
}
