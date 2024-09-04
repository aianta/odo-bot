package ca.ualberta.odobot;

import ca.ualberta.odobot.semanticflow.navmodel.DynamicXPath;
import io.vertx.core.json.JsonObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static ca.ualberta.odobot.snippets.Extractor.getSnippets;

public class JSoupXpathBehaviorTest {

    private static final Logger log = LoggerFactory.getLogger(JSoupXpathBehaviorTest.class);


    @Test
    void test(){

        /**
         * How does selectXPath work when running on a child element? Can I give a relative xpath
         * starting from that child element?
         */

        String html = """
                <html>
                    <body>
                        <div>
                            <span>some text</span>
                            <div>
                                <p>Some other text.</p>
                            </div>
                        </div>
                    </body>
                </html>
                """;

        Document document = Jsoup.parse(html);

        String xpath1 = "/html/body";

        Elements result1 = document.selectXpath(xpath1);

        log.info("Elements size: {}\n{} ", result1.size(), result1.get(0).outerHtml());

        String xpath2 = "/div/span";

        String xpath3 = "/html/body/div/span";

        //Elements result2 = result1.get(0).selectXpath(xpath2);

        //log.info("Elements size: {}\n{}", result2.size(), result2.get(0).outerHtml());

        //OK, so the xpath cannot be relative.

        Elements result3 = result1.get(0).selectXpath(xpath3);
        log.info("Elements size: {}\n{}", result3.size(), result3.get(0).outerHtml());

    }

    @Test
    void problemHtmlTest(){

        DynamicXPath xPath = new DynamicXPath();
        xPath.setPrefix("/html/body");
        xPath.setDynamicTag("div");
        xPath.setSuffix("/span/span/div/div/div/div/div/span/form/button");

        JsonObject input = new JsonObject()
                .put("eventDetails_domSnapshot", new JsonObject().put("outerHTML", problemHTML).encode());

        getSnippets(xPath, input).onSuccess(
                snippets->{
                    log.info("found {} snippets", snippets.size());

                    log.info("{}", snippets.get(0));
                }
        );



    }


    private static final String problemHTML = """
            <html class="" dir="ltr" lang="en-CA"><head>
              <meta charset="utf-8">
              <link rel="preload" href="/dist/fonts/lato/extended/Lato-Regular-bd03a2cc27.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
              <link rel="preload" href="/dist/fonts/lato/extended/Lato-Bold-cccb897485.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
              <link rel="preload" href="/dist/fonts/lato/extended/Lato-Italic-4eb103b4d1.woff2" as="font" type="font/woff2" crossorigin="anonmyous">
              <link rel="stylesheet" media="screen" href="/dist/brandable_css/no_variables/bundles/fonts-8022a0771c.css">
               \s
             \s
              <link rel="shortcut icon" type="image/x-icon" href="/dist/images/favicon-green-19a39adc12.ico">
              <link rel="apple-touch-icon" href="/dist/images/apple-touch-icon-585e5d997d.png">
              <link rel="stylesheet" media="all" href="/dist/brandable_css/default/variables-7dd4b80918af0e0218ec0229e4bd5873.css">
              <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/common-b3fb5311c1.css">
             \s
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="theme-color" content="#394B58">
              <meta name="robots" content="noindex,nofollow">
              <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/wiki_page-3732ff663a.css">
            <link rel="stylesheet" media="all" href="/dist/brandable_css/new_styles_normal_contrast/bundles/tinymce-5f707bbfa3.css">
             \s
             \s
                
                
             \s
                
              <link rel="preload" href="/dist/brandable_css/default/variables-7dd4b80918af0e0218ec0229e4bd5873.js" as="script" type="text/javascript"><link rel="preload" href="/dist/timezone/America/Denver-40670c6af7.js" as="script" type="text/javascript"><link rel="preload" href="/dist/timezone/America/Denver-40670c6af7.js" as="script" type="text/javascript"><link rel="preload" href="/dist/timezone/en_CA-21c9309bc0.js" as="script" type="text/javascript"><link rel="preload" href="/dist/webpack-dev/moment/locale/en-ca-c-159d1bb513.js" as="script" type="text/javascript"><link rel="preload" href="/dist/webpack-dev/main-e-37dbb7887e.js" as="script" type="text/javascript" crossorigin="anonymous"><link rel="preload" href="/dist/webpack-dev/wiki_page_index-c-ba106c9bd6.js" as="script" type="text/javascript"><link rel="preload" href="/dist/webpack-dev/navigation_header-c-faecaaa8e1.js" as="script" type="text/javascript">
              <title>Psychology - S2 - 2017 - Kazmaier: Pages</title>
                
                
            </head>
                
            <body class="with-left-side course-menu-expanded padless-content pages primary-nav-expanded context-course_114 webkit safari ff no-touch index">
                
            <noscript aria-hidden="true">
              <div role="alert" class="ic-flash-static ic-flash-error">
                <div class="ic-flash__icon" aria-hidden="true">
                  <i class="icon-warning"></i>
                </div>
                <h1>You need to have JavaScript enabled in order to access this site.</h1>
              </div>
            </noscript>
                
                
                
                
            <div id="flash_message_holder" aria-hidden="true"><div class="ic-flash-success flash-message-container" aria-hidden="true" style="z-index: 2;">
                      <div class="ic-flash__icon">
                        <i class="icon-check"></i>
                      </div>
                      The page "Notification Preferences-2" has been deleted.
                      <button type="button" class="Button Button--icon-action close_link" aria-label="Close">
                        <i class="icon-x"></i>
                      </button>
                    </div></div>
            <div id="flash_screenreader_holder" role="alert" aria-live="assertive" aria-relevant="additions" class="screenreader-only" aria-atomic="true"><span>
                      The page "Notification Preferences-2" has been deleted.
                      Close
                    </span></div>
                
            <div id="application" class="ic-app" aria-hidden="true">
             \s
                
                
                
            <header id="mobile-header" class="no-print">
              <button type="button" class="Button Button--icon-action-rev Button--large mobile-header-hamburger">
                <i class="icon-solid icon-hamburger"></i>
                <span id="mobileHeaderInboxUnreadBadge" class="menu-item__badge" style="min-width: 0; top: 12px; height: 12px; right: 6px; display:none;"></span>
                <span class="screenreader-only">Dashboard</span>
              </button>
              <div class="mobile-header-space"></div>
                <a class="mobile-header-title expandable" href="/courses/114" role="button" aria-controls="mobileContextNavContainer">
                  <div>Psychology S2</div>
                    <div>Pages</div>
                </a>
                <a class="Button Button--icon-action-rev Button--large mobile-header-student-view" id="mobile-student-view" aria-label="Student View" role="button" rel="nofollow" data-method="post" href="/courses/114/student_view/1">
                  <i class="icon-student-view"></i>
            </a>    <button type="button" class="Button Button--icon-action-rev Button--large mobile-header-arrow" aria-label="Navigation Menu">
                  <i class="icon-arrow-open-down" id="mobileHeaderArrowIcon"></i>
                </button>
            </header>
            <nav id="mobileContextNavContainer"></nav>
                
            <header id="header" class="ic-app-header no-print ">
              <a href="#content" id="skip_navigation_link">Skip To Content</a>
              <div role="region" class="ic-app-header__main-navigation" aria-label="Global Navigation">
                  <div class="ic-app-header__logomark-container">
                    <a href="http://localhost:8088/" class="ic-app-header__logomark">
                      <span class="screenreader-only">Dashboard</span>
                    </a>
                  </div>
                <ul id="menu" class="ic-app-header__menu-list">
                    <li class="menu-item ic-app-header__menu-list-item  ic-app-header__menu-list-item--active" aria-current="page">
                      <button id="global_nav_profile_link" class="ic-app-header__menu-list-link">
                        <div class="menu-item-icon-container" style="">
                          <div aria-hidden="true" class="fs-exclude ic-avatar ">
                            <img src="http://canvas.instructure.com/images/messages/avatar-50.png" alt="ianta@ualberta.ca">
                          </div>
                          <span class="menu-item__badge"></span>
                        </div>
                        <div class="menu-item__text">
                          Account
                        </div>
                      </button>
                    </li>
                    <li class="menu-item ic-app-header__menu-list-item ">
                      <button id="global_nav_accounts_link" class="ic-app-header__menu-list-link">
                        <div class="menu-item-icon-container" aria-hidden="true">
                          <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" class="ic-icon-svg ic-icon-svg--accounts" x="0" y="0" viewBox="0 0 200 224" enable-background="new 0 0 200 224" xml:space="preserve"></svg>
                
                        </div>
                        <div class="menu-item__text">
                          Admin
                        </div>
                      </button>
                    </li>
                  <li class="ic-app-header__menu-list-item ">
                    <a id="global_nav_dashboard_link" href="http://localhost:8088/" class="ic-app-header__menu-list-link">
                      <div class="menu-item-icon-container" aria-hidden="true">
                        <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--dashboard" version="1.1" x="0" y="0" viewBox="0 0 280 200" enable-background="new 0 0 280 200" xml:space="preserve"></svg>
                
                      </div>
                      <div class="menu-item__text">Dashboard</div>
                    </a>
                  </li>
                    <li class="menu-item ic-app-header__menu-list-item">
                      <button id="global_nav_courses_link" class="ic-app-header__menu-list-link">
                        <div class="menu-item-icon-container" aria-hidden="true">
                          <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--courses" version="1.1" x="0" y="0" viewBox="0 0 280 259" enable-background="new 0 0 280 259" xml:space="preserve"></svg>
                
                        </div>
                        <div class="menu-item__text">
                          Courses
                        </div>
                      </button>
                    </li>
                  <li class="menu-item ic-app-header__menu-list-item ">
                    <a id="global_nav_calendar_link" href="/calendar" class="ic-app-header__menu-list-link">
                      <div class="menu-item-icon-container" aria-hidden="true">
                        <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--calendar" version="1.1" x="0" y="0" viewBox="0 0 280 280" enable-background="new 0 0 280 280" xml:space="preserve"></svg>
                
                      </div>
                      <div class="menu-item__text">
                        Calendar
                      </div>
                    </a>
                  </li>
                  <li class="menu-item ic-app-header__menu-list-item ">
                    <a id="global_nav_conversations_link" href="/conversations" class="ic-app-header__menu-list-link">
                      <div class="menu-item-icon-container">
                        <span aria-hidden="true"><svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--inbox" version="1.1" x="0" y="0" viewBox="0 0 280 280" enable-background="new 0 0 280 280" xml:space="preserve"></svg>
            </span>
                        <span class="menu-item__badge"></span>
                      </div>
                      <div class="menu-item__text">
                        Inbox
                      </div>
                    </a>
                  </li>
                   \s
                
                
                  <li class="ic-app-header__menu-list-item">
                    <a id="global_nav_help_link" role="button" class="ic-app-header__menu-list-link" data-track-category="help system" data-track-label="help button" href="#">
                      <div class="menu-item-icon-container" role="presentation">
                          <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg menu-item__icon svg-icon-help" version="1.1" x="0" y="0" viewBox="0 0 200 200" enable-background="new 0 0 200 200" xml:space="preserve" fill="currentColor"></svg>
                
                        <span class="menu-item__badge"></span>
                      </div>
                      <div class="menu-item__text">
                        Help
                      </div>
            </a>      </li>
                </ul>
              </div>
              <div class="ic-app-header__secondary-navigation">
                <ul class="ic-app-header__menu-list">
                  <li class="menu-item ic-app-header__menu-list-item">
                    <button id="primaryNavToggle" class="ic-app-header__menu-list-link ic-app-header__menu-list-link--nav-toggle" aria-label="Minimize global navigation" title="Minimize global navigation">
                      <div class="menu-item-icon-container" aria-hidden="true">
                        <svg xmlns="http://www.w3.org/2000/svg" class="ic-icon-svg ic-icon-svg--navtoggle" version="1.1" x="0" y="0" width="40" height="32" viewBox="0 0 40 32" xml:space="preserve">
             \s
            </svg>
                
                      </div>
                    </button>
                  </li>
                </ul>
              </div>
              <div id="global_nav_tray_container"></div>
              <div id="global_nav_tour"></div>
            </header>
                
                
              <div id="instructure_ajax_error_box">
                <div style="text-align: right; background-color: #fff;"><a href="#" class="close_instructure_ajax_error_box_link">Close</a></div>
                <iframe id="instructure_ajax_error_result" src="about:blank" style="border: 0;" title="Error"></iframe>
              </div>
                
              <div id="wrapper" class="ic-Layout-wrapper">
                  <div class="ic-app-nav-toggle-and-crumbs no-print">
                      <button type="button" id="courseMenuToggle" class="Button Button--link ic-app-course-nav-toggle" aria-live="polite" aria-label="Hide Courses Navigation Menu">
                        <i class="icon-hamburger" aria-hidden="true"></i>
                      </button>
                
                    <div class="ic-app-crumbs">
                        <nav id="breadcrumbs" role="navigation" aria-label="breadcrumbs"><ul><li class="home"><a href="/"><span class="ellipsible"><i class="icon-home" title="My Dashboard">
              <span class="screenreader-only">My Dashboard</span>
            </i>
            </span></a></li><li><a href="/courses/114"><span class="ellipsible">Psychology S2</span></a></li><li><a href="/courses/114/pages"><span class="ellipsible">Pages</span></a></li></ul></nav>
                    </div>
                
                
                    <div class="right-of-crumbs">
                        <a class="btn" id="easy_student_view" rel="nofollow" data-method="post" href="/courses/114/student_view/1">
                          <i class="icon-student-view"></i> Student View
            </a>        </div>
                
                  </div>
                <div id="main" class="ic-Layout-columns">
                    <div class="ic-Layout-watermark"></div>
                    <div id="left-side" class="ic-app-course-menu ic-sticky-on list-view" style="display: block">
                      <div id="sticky-container" class="ic-sticky-frame">
                        <nav role="navigation" aria-label="Courses Navigation Menu"><ul id="section-tabs"><li class="section"><a href="/courses/114" class="home" tabindex="0">Home</a></li><li class="section section-hidden"><a href="/courses/114/announcements" title="No content. Not visible to students" aria-label="Announcements. No content. Not visible to students" class="announcements" data-tooltip="" tabindex="0">Announcements<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li><li class="section"><a href="/courses/114/assignments" class="assignments" tabindex="0">Assignments</a></li><li class="section"><a href="/courses/114/discussion_topics" class="discussions" tabindex="0">Discussions</a></li><li class="section"><a href="/courses/114/grades" class="grades" tabindex="0">Grades</a></li><li class="section"><a href="/courses/114/users" class="people" tabindex="0">People</a></li><li class="section section-hidden"><a href="/courses/114/wiki" title="No content. Not visible to students" aria-label="Pages. No content. Not visible to students" aria-current="page" class="pages active" data-tooltip="" tabindex="0">Pages<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li><li class="section section-hidden"><a href="/courses/114/files" title="No content. Not visible to students" aria-label="Files. No content. Not visible to students" class="files" data-tooltip="" tabindex="0">Files<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li><li class="section"><a href="/courses/114/assignments/syllabus" class="syllabus" tabindex="0">Syllabus</a></li><li class="section section-hidden"><a href="/courses/114/outcomes" title="No content. Not visible to students" aria-label="Outcomes. No content. Not visible to students" class="outcomes" data-tooltip="" tabindex="0">Outcomes<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li><li class="section"><a href="/courses/114/rubrics" class="rubrics" tabindex="0">Rubrics</a></li><li class="section"><a href="/courses/114/quizzes" class="quizzes" tabindex="0">Quizzes</a></li><li class="section section-hidden"><a href="/courses/114/modules" title="No content. Not visible to students" aria-label="Modules. No content. Not visible to students" class="modules" data-tooltip="" tabindex="0">Modules<i class="nav-icon icon-off" aria-hidden="true" role="presentation"></i></a></li><li class="section"><a href="/courses/114/settings" class="settings" tabindex="0">Settings</a></li></ul></nav>
                      </div>
                    </div>
                  <div id="not_right_side" class="ic-app-main-content">
                    <div id="content-wrapper" class="ic-Layout-contentWrapper">
                     \s
                      <div id="content" class="ic-Layout-contentMain" role="main">
                       \s
                
                      <div class="collectionView"><h1 class="screenreader-only">Pages</h1>
                
            <div class="header-bar-outer-container">
              <div class="sticky-toolbar-with-right-side" data-sticky="">
                <div class="header-bar">
                  <div class="header-bar-right">
                   \s
                      <button class="btn delete_pages" tabindex="0" aria-label="Delete selected pages" disabled="">
                        <i class="icon-trash" role="presentation"></i>
                      </button>
                   \s
                   \s
                      <a class="btn btn-primary icon-plus new_page" role="button" tabindex="0" aria-label="Add a page">Page</a>
                     \s
                   \s
                  </div>
                </div>
              </div>
            </div>
                
                
            <div id="external-tool-mount-point"></div>
            <div id="copy-to-mount-point"></div>
            <div id="send-to-mount-point"></div>
            <div class="index-content-container">
              <div class="index-content">
               \s
                 \s
               \s
               \s
               \s
                 \s
                    <div style="text-align: center">No pages created yet. <a class="new_page" href="#">Add one!</a></div>
                 \s
               \s
              </div>
            </div>
            </div></div>
                    </div>
                    <div id="right-side-wrapper" class="ic-app-main-content__secondary">
                      <aside id="right-side" role="complementary">
                       \s
                      </aside>
                    </div>
                  </div>
                </div>
              </div>
                
                
                
                <div style="display:none;"><!-- Everything inside of this should always stay hidden -->
                </div>
              <div id="aria_alerts" class="hide-text affix" role="alert" aria-live="assertive"></div>
              <div id="StudentTray__Container"></div>
             \s
                
             \s
                
                
             \s
                
            </div> <!-- #application -->
                
                
            <div class="tinymce-a11y-checker-container" aria-hidden="true"></div><div id="nav-tray-portal" style="position: relative; z-index: 99;"><span dir="ltr" style="--Tray__fLzZc-smallWidth: 28em;" data-cid="Tray"><span class="fLzZc_bGBk fLzZc_fSpQ fLzZc_doqw fLzZc_bxia eJkkQ_cPWt eJkkQ_fvoZ" style="--Tray__fLzZc-smallWidth: 28em;" data-cid="Transition"><div role="dialog" aria-label="Profile tray"><div class="fLzZc_caGd"><div class="navigation-tray-container profile-tray"><span class="ejhDx_bGBk ejhDx_bQpq ejhDx_coHh" data-cid="CloseButton"><button cursor="pointer" type="button" tabindex="0" class="fOyUs_bGBk fOyUs_fKyb fOyUs_cuDs fOyUs_cBHs fOyUs_eWbJ fOyUs_fmDy fOyUs_eeJl fOyUs_cBtr fOyUs_fuTR fOyUs_cnfU fQfxa_bGBk" style="margin: 0px; padding: 0px; border-radius: 0.25rem; border-width: 0px; width: auto; cursor: pointer;" data-cid="BaseButton"><span class="fQfxa_caGd fQfxa_VCXp fQfxa_buuG fQfxa_EMjX fQfxa_bCUx fQfxa_bVmg fQfxa_bIHL"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_eRIA bDzpk_fZWR bDzpk_qOas" style="width: 100%; height: 100%;"><span class="fOyUs_bGBk dJCgj_bGBk"><span class="fQfxa_eoCh"><svg name="IconX" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_eXrk cGqzL_bGBk" data-cid="InlineSVG SVGIcon"><g role="presentation"></g></svg></span><span class="ergWt_bGBk">Close</span></span></span></span></button></span><div class="tray-with-space-for-global-nav"><div class="fOyUs_bGBk" style="padding: 1.5rem;"><span class="fOyUs_bGBk fOyUs_ImeN"><span name="ianta@ualberta.ca" src="http://canvas.instructure.com/images/messages/avatar-50.png" alt="User profile picture" data-fs-exclude="true" color="default" shape="circle" aria-label="User profile picture" role="img" class="fOyUs_bGBk fOyUs_UeJS elMgC_bGBk elMgC_eoMd elMgC_cJVF elMgC_ddES elMgC_cHTY" style="margin: auto; background-image: url(&quot;http://canvas.instructure.com/images/messages/avatar-50.png&quot;);" data-cid="Avatar"><img src="http://canvas.instructure.com/images/messages/avatar-50.png" class="elMgC_MrJH" alt="User profile picture" aria-hidden="true"></span><div style="word-break: break-word;"><h2 class="fOyUs_bGBk blnAQ_bGBk blnAQ_dnfM blnAQ_drOs" data-cid="Heading">ianta@ualberta.ca</h2></div><form action="/logout" method="post"><input name="utf8" type="hidden" value="✓"><input name="_method" type="hidden" value="delete"><input name="authenticity_token" type="hidden" value="JsWk/zJ8pD4VpVR9YXfhG4GsyG/DUK9iZXJrtdiBbflsj+WOWg3XXzrmPkoXOaRcxOWMNvISwhYHSgfhlu8Xtw=="><button cursor="pointer" type="submit" class="fOyUs_bGBk fOyUs_fKyb fOyUs_cuDs fOyUs_cBHs fOyUs_eWbJ fOyUs_fmDy fOyUs_eeJl fOyUs_cBtr fOyUs_fuTR fOyUs_cnfU fQfxa_bGBk" style="margin: 1.5rem 0px 0.5rem; padding: 0px; border-radius: 0.25rem; border-width: 0px; width: auto; cursor: pointer;" data-cid="BaseButton Button"><span class="fQfxa_caGd fQfxa_VCXp fQfxa_buuG fQfxa_ImeN undefined fQfxa_dqAF"><span class="fQfxa_biBD">Logout</span></span></button></form></span><hr role="presentation"><ul class="fOyUs_bGBk fOyUs_UeJS fClCc_bGBk fClCc_fLbg" style="margin: 0px;" data-cid="List"><li class="fOyUs_bGBk jpyTq_bGBk jpyTq_ycrn jpyTq_bCcs" style="padding: 0px; max-width: 100%;" data-cid="ListItem"><div style="text-align: center;"><div class="fOyUs_bGBk eHQDY_bGBk eHQDY_ycrn eHQDY_ddES" style="margin: 1.5rem;" data-cid="Spinner"><svg class="eHQDY_cJVF" role="img" aria-labelledby="Spinner__uY3LUujL2KD7" focusable="false"><title id="Spinner__uY3LUujL2KD7">Loading</title><g role="presentation"><circle class="eHQDY_dTxv" cx="50%" cy="50%" r="1.75em"></circle><circle class="eHQDY_eWAY" cx="50%" cy="50%" r="1.75em"></circle></g></svg></div></div></li></ul><hr role="presentation"><div class="fOyUs_bGBk" style="margin: 0px;"><div class="epRMX_bGBk"><input id="Checkbox__usybOzHnmks6" type="checkbox" class="epRMX_cwos" value=""><label for="Checkbox__usybOzHnmks6" class="epRMX_bOnW"><span class="faJyW_bGBk" style="--ToggleFacade__faJyW-background: #2D3B45; --ToggleFacade__faJyW-borderColor: #2D3B45; --ToggleFacade__faJyW-checkedBackground: #127A1B; --ToggleFacade__faJyW-checkedIconColor: #127A1B; --ToggleFacade__faJyW-focusOutlineColor: #0770A3;"><span class="faJyW_cSXm faJyW_bYta faJyW_doqw" aria-hidden="true"><span class="faJyW_dnnz"><span class="faJyW_cMpH"><svg name="IconX" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_eXrk cGqzL_bGBk faJyW_eoCh" data-cid="InlineSVG SVGIcon"><g role="presentation"></g></svg></span></span></span><span class="faJyW_blJt faJyW_bYta"><span class="fOyUs_bGBk"><span wrap="normal" letter-spacing="normal" class="enRcg_bGBk enRcg_ycrn enRcg_eQnG">Use High Contrast UI</span><span data-position="Popover__uVPeZy6yTZHm"><button aria-describedby="Tooltip__uxVebY3npzZx" data-popover-trigger="true" data-position-target="Popover__uVPeZy6yTZHm" cursor="pointer" type="button" tabindex="0" class="fOyUs_bGBk fOyUs_fKyb fOyUs_cuDs fOyUs_cBHs fOyUs_eWbJ fOyUs_fmDy fOyUs_eeJl fOyUs_cBtr fOyUs_fuTR fOyUs_cnfU fQfxa_bGBk" style="margin: 0px 0px 0.375rem 0.375rem; padding: 0px; border-radius: 0.25rem; border-width: 0px; width: auto; cursor: pointer;" data-cid="BaseButton IconButton"><span class="fQfxa_caGd fQfxa_VCXp fQfxa_buuG fQfxa_EMjX fQfxa_bCUx fQfxa_bVmg fQfxa_bIHL"><span direction="row" wrap="no-wrap" class="fOyUs_bGBk fOyUs_desw bDzpk_bGBk bDzpk_eRIA bDzpk_fZWR bDzpk_qOas" style="width: 100%; height: 100%;"><span class="fOyUs_bGBk dJCgj_bGBk"><span class="fQfxa_eoCh"><svg name="IconInfo" viewBox="0 0 1920 1920" rotate="0" style="width: 1em; height: 1em;" width="1em" height="1em" aria-hidden="true" role="presentation" focusable="false" class="dUOHu_bGBk dUOHu_drOs dUOHu_eXrk cGqzL_bGBk" data-cid="InlineSVG SVGIcon"><g role="presentation"></g></svg></span><span class="ergWt_bGBk">Toggle tooltip</span></span></span></span></button></span></span></span></span></label></div></div></div></div></div></div></div></span></span></div><span dir="ltr" aria-hidden="true"><span data-position-content="Popover__uVPeZy6yTZHm" class="fOyUs_bGBk fOyUs_cuDs fOyUs_fQrx dqmEK_ftAV dqmEK_fjSW" style="border-width: 0px; top: 0px; left: -9999em; position: absolute; overflow: hidden; pointer-events: none; display: none;"><span class="fOyUs_bGBk fOyUs_dnJm fOyUs_dzKA fOyUs_EMjX fOyUs_elGp fOyUs_UeJS dqmEK_caGd" style="border-radius: 0.25rem; border-width: 0.0625rem; width: auto; height: auto;"><span class="dqmEK_fAVq dqmEK_ejeM dqmEK_bDBw"></span><span id="Tooltip__uxVebY3npzZx" class="eZLSb_bGBk" role="tooltip">Enhances the color contrast of text, buttons, etc.</span></span></span></span></body></html>
    """;

}
